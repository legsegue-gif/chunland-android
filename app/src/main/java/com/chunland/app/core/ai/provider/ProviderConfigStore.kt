package com.chunland.app.core.ai.provider

import android.util.Log
import com.chunland.app.core.ai.SystemAiProvider
import com.chunland.app.core.ai.storage.AiDatabase
import com.chunland.app.core.ai.storage.AiSchema
import com.chunland.app.core.ai.storage.SqlRow
import com.chunland.app.core.ai.storage.SqlValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 来源配置聚合（对齐 iOS ProviderConfigStore.swift）。
 *
 * 全部 AI 来源配置的唯一入口。**这是当前散在多处的配置解析的替代物** ——
 * 旧实现里配置读取散在 AiSettings 直读、聊天链路、单次调用链路各一份，
 * 改一个字段要改多个地方，还各自有不同的兜底逻辑。
 *
 * 落 SQLite（密钥除外，见 [ProviderCredentials]）。
 */
class ProviderConfigStore(
    private val db: AiDatabase,
    private val credentials: ProviderCredentials,
) {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    // 内存快照 —— 配置读远多于写，每次查询都打库没必要
    @Volatile private var instances: List<ProviderInstance> = emptyList()
    @Volatile private var entries: List<ModelEntry> = emptyList()
    @Volatile private var groups: List<ModelGroup> = emptyList()
    @Volatile private var defaultGroupId: String? = null
    @Volatile private var loaded = false

    companion object {
        private const val TAG = "ProviderConfig"
    }

    // MARK: - 加载与播种

    suspend fun loadIfNeeded() = mutex.withLock {
        if (loaded) return@withLock
        reloadLocked()
        if (meta(AiSchema.MetaKey.SEEDED) == null) {
            seedDefaults()
            setMeta(AiSchema.MetaKey.SEEDED, "1")
        }
        loaded = true
    }

    suspend fun reload() = mutex.withLock { reloadLocked() }

    private suspend fun reloadLocked() {
        instances = db.query("SELECT * FROM provider_instances;").mapNotNull(::decodeInstance)
        entries = db.query("SELECT * FROM model_entries;").mapNotNull(::decodeEntry)
        groups = db.query("SELECT * FROM model_groups;").mapNotNull(::decodeGroup)
        defaultGroupId = meta(AiSchema.MetaKey.DEFAULT_GROUP_ID)
    }

    /**
     * 首次启动播种：建系统 AI 来源 + 默认组。
     *
     * 默认组只放系统 AI —— 用户自配的来源要等他自己添加。
     * 一旦添加，[ensureInDefaultGroup] 会把它追加到组尾，
     * 于是自动获得「系统 AI 优先、自配兜底」。
     */
    private suspend fun seedDefaults() {
        val system = ProviderInstance.system()
        upsertLocked(system)

        // 系统 AI 的模型属性**一个都不落库** —— 它们随模块预设与服务端下发变化，
        // 落库就等于把「真相源」钉死成首装那一刻的快照，此后改预设、发新版全部失效。
        // 这里存的是占位死值，读出口的 applyingSystemPreset 会无条件覆盖。
        val entry = ModelEntry(
            instanceId = system.id,
            modelId = ModelEntry.SYSTEM_MODEL_SENTINEL,
            displayName = "系统提供的 AI",
            contextWindow = 32_000,
            maxOutputTokens = 4_096,
            supportsVision = false,
        )
        upsertLocked(entry)

        val group = ModelGroup(
            id = ModelGroup.DEFAULT_GROUP_ID,
            name = "默认",
            memberEntryIds = listOf(entry.id),
        )
        upsertLocked(group)
        setMeta(AiSchema.MetaKey.DEFAULT_GROUP_ID, group.id)
        Log.i(TAG, "已播种默认 AI 配置")
    }

    // MARK: - 查询

    fun allInstances(): List<ProviderInstance> = instances
    fun allEntries(): List<ModelEntry> = entries.map(::applyingSystemPreset)
    fun allGroups(): List<ModelGroup> = groups

    fun instance(id: String): ProviderInstance? = instances.firstOrNull { it.id == id }
    fun entry(id: String): ModelEntry? = entries.firstOrNull { it.id == id }?.let(::applyingSystemPreset)
    fun group(id: String): ModelGroup? = groups.firstOrNull { it.id == id }

    /**
     * 系统 AI 条目的模型属性一律现取 —— 库里存的是占位死值。
     *
     * **落点必须在读出口，不能只改造 provider 的那一处**：contextWindow 被
     * [com.chunland.app.core.ai.session.AiChatSession] 拿去喂 ContextPolicy、
     * supportsVision 决定图片编不编进请求，三条链路各取所需，只改一处必漏。
     *
     * 覆盖是**无条件**的（不做「库里有值就用库里的」），否则占位死值会赢。
     * 模块未接入时 preset 为 null，此时系统 AI 整体不可用，返回原样即可。
     */
    private fun applyingSystemPreset(entry: ModelEntry): ModelEntry {
        if (entry.instanceId != ProviderInstance.SYSTEM_INSTANCE_ID) return entry
        val preset = SystemAiProvider.preset ?: return entry
        return entry.copy(
            modelId = preset.model,
            contextWindow = preset.contextWindow,
            maxOutputTokens = preset.maxOutputTokens,
            supportsVision = preset.supportsVision,
        )
    }

    fun defaultGroup(): ModelGroup? =
        defaultGroupId?.let { group(it) } ?: groups.firstOrNull()

    /**
     * 组的可用成员 —— 过滤掉来源被停用、来源已删、或（自配来源）缺密钥的条目。
     *
     * 「缺密钥」必须在这里就滤掉：让一个必然 401 的条目参与降级，
     * 只会白白多一轮请求 + 一条误导性的「密钥无效」提示。
     */
    fun usableEntries(group: ModelGroup): List<ModelEntry> =
        group.memberEntryIds.mapNotNull { id ->
            val entry = entry(id) ?: return@mapNotNull null
            val inst = instance(entry.instanceId) ?: return@mapNotNull null
            if (!inst.isEnabled || inst.kind == ProviderKind.UNSUPPORTED) return@mapNotNull null
            if (inst.kind.usesStoredApiKey && credentials.apiKey(inst.id) == null) return@mapNotNull null
            entry
        }

    /** 解析会话该用哪个模型：绑定 → 组首个可用 → 默认组首个可用 */
    fun resolveEntry(binding: SessionModelBinding?): ModelEntry? = when (binding) {
        // 显式钉死的模型：即使不可用也如实返回 null，不静默改用别的 ——
        // 用户选了什么就该用什么，换模型必须是他自己的动作。
        is SessionModelBinding.Entry -> entry(binding.entryId)
        is SessionModelBinding.Group ->
            group(binding.groupId)?.let { usableEntries(it).firstOrNull() }
                ?: defaultGroup()?.let { usableEntries(it).firstOrNull() }
        null -> defaultGroup()?.let { usableEntries(it).firstOrNull() }
    }

    /** 当前是否有任何可用模型 —— UI 据此显示配置引导 */
    fun hasUsableModel(): Boolean = resolveEntry(null) != null

    // MARK: - 写入

    suspend fun upsert(instance: ProviderInstance) = mutex.withLock {
        upsertLocked(instance)
        reloadLocked()
    }

    suspend fun upsert(entry: ModelEntry) = mutex.withLock {
        upsertLocked(entry)
        reloadLocked()
    }

    suspend fun upsert(group: ModelGroup) = mutex.withLock {
        upsertLocked(group)
        reloadLocked()
    }

    private suspend fun upsertLocked(instance: ProviderInstance) {
        db.execute(
            """
            INSERT INTO provider_instances (id, label, kind, base_url, is_enabled, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              label = excluded.label,
              kind = excluded.kind,
              base_url = excluded.base_url,
              is_enabled = excluded.is_enabled
            """.trimIndent(),
            listOf(
                SqlValue.text(instance.id), SqlValue.text(instance.label),
                SqlValue.text(instance.unknownKindRaw ?: instance.kind.wire),
                SqlValue.of(instance.baseUrl), SqlValue.bool(instance.isEnabled),
                SqlValue.long(instance.createdAt),
            )
        )
    }

    private suspend fun upsertLocked(entry: ModelEntry) {
        db.execute(
            """
            INSERT INTO model_entries
              (id, instance_id, model_id, display_name, context_window, max_output_tokens, supports_vision)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              display_name = excluded.display_name,
              context_window = excluded.context_window,
              max_output_tokens = excluded.max_output_tokens,
              supports_vision = excluded.supports_vision
            """.trimIndent(),
            listOf(
                SqlValue.text(entry.id), SqlValue.text(entry.instanceId),
                SqlValue.text(entry.modelId), SqlValue.text(entry.displayName),
                SqlValue.int(entry.contextWindow), SqlValue.int(entry.maxOutputTokens),
                SqlValue.bool(entry.supportsVision),
            )
        )
    }

    private suspend fun upsertLocked(group: ModelGroup) {
        val members = json.encodeToString(ListSerializer(String.serializer()), group.memberEntryIds)
        db.execute(
            """
            INSERT INTO model_groups (id, name, member_ids, strategy, fallback_strategy)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              name = excluded.name,
              member_ids = excluded.member_ids,
              strategy = excluded.strategy,
              fallback_strategy = excluded.fallback_strategy
            """.trimIndent(),
            listOf(
                SqlValue.text(group.id), SqlValue.text(group.name), SqlValue.text(members),
                SqlValue.text(group.strategy.wire), SqlValue.text(group.fallbackStrategy.wire),
            )
        )
    }

    /**
     * 把某个来源下的模型条目整体换成 [entry]，并让降级链跟着改指。
     *
     * 为什么不能只 [upsert]：[ModelEntry.id] 是 `{instanceId}:{modelId}` 复合的，
     * **改一次模型名就等于换了一个条目** —— 直接 upsert 会留下一条指向旧模型的孤儿，
     * 而降级链里还指着那个已经没人维护的旧 id（界面上显示成「已删除」）。
     */
    suspend fun replaceEntry(entry: ModelEntry, forInstance: String) = mutex.withLock {
        val staleIds = entries
            .filter { it.instanceId == forInstance && it.id != entry.id }
            .map { it.id }
            .toSet()

        upsertLocked(entry)
        if (staleIds.isNotEmpty()) {
            staleIds.forEach {
                db.execute("DELETE FROM model_entries WHERE id = ?;", listOf(SqlValue.text(it)))
            }
            // 组成员里把旧 id 就地换成新 id —— 用「删了再追加」会把它挪到链尾，
            // 用户精心排的优先级就白排了。
            groups.filter { g -> g.memberEntryIds.any { it in staleIds } }.forEach { g ->
                val seen = mutableSetOf<String>()
                val ids = g.memberEntryIds
                    .map { if (it in staleIds) entry.id else it }
                    .filter { seen.add(it) }   // 新 id 可能本就在链上，去重
                upsertLocked(g.copy(memberEntryIds = ids))
            }
        }
        reloadLocked()
    }

    /** 删除来源。**密钥必须一并清掉** —— 库里的行走 CASCADE，本地存储不会自己走 */
    suspend fun deleteInstance(id: String) = mutex.withLock {
        if (id == ProviderInstance.SYSTEM_INSTANCE_ID) {
            Log.w(TAG, "拒绝删除系统 AI 来源")
            return@withLock
        }
        val removedEntryIds = entries.filter { it.instanceId == id }.map { it.id }.toSet()
        db.execute("DELETE FROM provider_instances WHERE id = ?;", listOf(SqlValue.text(id)))
        credentials.deleteApiKey(id)

        // 组里残留已删条目的 id 会让降级链踩空，顺手摘掉
        groups.filter { g -> g.memberEntryIds.any { it in removedEntryIds } }.forEach { g ->
            upsertLocked(g.copy(memberEntryIds = g.memberEntryIds.filterNot { it in removedEntryIds }))
        }
        reloadLocked()
    }

    /** 新增自配来源时把它追加进默认组尾部 —— 这样系统 AI 挂了能自动兜底 */
    suspend fun ensureInDefaultGroup(entryId: String) = mutex.withLock {
        val g = defaultGroup() ?: return@withLock
        if (entryId in g.memberEntryIds) return@withLock
        upsertLocked(g.copy(memberEntryIds = g.memberEntryIds + entryId))
        reloadLocked()
    }

    // MARK: - 会话绑定

    suspend fun binding(sessionId: String): SessionModelBinding? {
        val row = db.query(
            "SELECT kind, target_id FROM session_bindings WHERE session_id = ? LIMIT 1;",
            listOf(SqlValue.text(sessionId))
        ).firstOrNull() ?: return null
        val kind = row.string("kind")?.let(AiSchema.BindingKind::from) ?: return null
        val target = row.string("target_id") ?: return null
        return when (kind) {
            AiSchema.BindingKind.GROUP -> SessionModelBinding.Group(target)
            AiSchema.BindingKind.ENTRY -> SessionModelBinding.Entry(target)
        }
    }

    suspend fun setBinding(binding: SessionModelBinding, sessionId: String) {
        val (kind, target) = when (binding) {
            is SessionModelBinding.Group -> AiSchema.BindingKind.GROUP to binding.groupId
            is SessionModelBinding.Entry -> AiSchema.BindingKind.ENTRY to binding.entryId
        }
        db.execute(
            """
            INSERT INTO session_bindings (session_id, kind, target_id) VALUES (?, ?, ?)
            ON CONFLICT(session_id) DO UPDATE SET kind = excluded.kind, target_id = excluded.target_id
            """.trimIndent(),
            listOf(SqlValue.text(sessionId), SqlValue.text(kind.wire), SqlValue.text(target))
        )
    }

    // MARK: - 单例配置

    suspend fun meta(key: String): String? =
        db.query("SELECT value FROM provider_meta WHERE key = ? LIMIT 1;", listOf(SqlValue.text(key)))
            .firstOrNull()?.string("value")

    suspend fun setMeta(key: String, value: String) {
        db.execute(
            """
            INSERT INTO provider_meta (key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent(),
            listOf(SqlValue.text(key), SqlValue.text(value))
        )
        if (key == AiSchema.MetaKey.DEFAULT_GROUP_ID) defaultGroupId = value
    }

    // MARK: - 解码

    private fun decodeInstance(row: SqlRow): ProviderInstance? {
        val id = row.string("id") ?: return null
        val label = row.string("label") ?: return null
        val kindRaw = row.string("kind") ?: return null
        val kind = ProviderKind.decoded(kindRaw)
        return ProviderInstance(
            id = id,
            label = label,
            kind = kind,
            baseUrl = row.string("base_url"),
            isEnabled = row.bool("is_enabled") ?: true,
            createdAt = row.epochMillis("created_at") ?: 0L,
            unknownKindRaw = if (kind == ProviderKind.UNSUPPORTED) kindRaw else null,
        )
    }

    private fun decodeEntry(row: SqlRow): ModelEntry? {
        val instanceId = row.string("instance_id") ?: return null
        val modelId = row.string("model_id") ?: return null
        return ModelEntry(
            instanceId = instanceId,
            modelId = modelId,
            displayName = row.string("display_name") ?: modelId,
            contextWindow = row.int("context_window") ?: 32_000,
            maxOutputTokens = row.int("max_output_tokens") ?: 4_096,
            supportsVision = row.bool("supports_vision") ?: false,
        )
    }

    private fun decodeGroup(row: SqlRow): ModelGroup? {
        val id = row.string("id") ?: return null
        val name = row.string("name") ?: return null
        val membersRaw = row.string("member_ids") ?: return null
        val members = runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), membersRaw)
        }.getOrDefault(emptyList())
        return ModelGroup(
            id = id,
            name = name,
            memberEntryIds = members,
            strategy = RoutingStrategy.decoded(row.string("strategy")),
            fallbackStrategy = FallbackStrategy.decoded(row.string("fallback_strategy")),
        )
    }
}
