package com.chunland.app.core.ai.tools

import android.util.Log
import com.chunland.app.core.ai.domain.AgentParamType
import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.domain.AgentToolParam
import com.chunland.app.data.api.AiToolSchemaResponse
import com.chunland.app.core.security.KeyValueStore
import com.chunland.app.data.api.AiWireToolDto
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

// MARK: - 线上下发的工具（wire tools）
//
// R5 之后工具体在服务端，但 schema 仍钉死在端上 —— 于是「加一个只读查询工具」
// 这种纯增量的事，仍要改两端代码并各自重新发布。
//
// 这里放开的正是这一件事：**只读工具的 schema 可以由服务端下发**
// （`GET /ai/tools/schema`），新增只读工具不必改端上代码。
//
// ⚠️ **变更工具永远不下发，永远钉死在端上。** 判据是安不安全，不是新不旧：
// `kind: MUTATION` 决定弹不弹 HITL 确认框，那是模型「想下单」与「真下单」
// 之间唯一的人工闸门。它若由下发内容决定，一个服务端 bug 就能让确认框静默消失。
//
// 这条不是靠纪律守的，是靠**类型**守的 —— 见下面 [AgentRemoteToolSpec] 的注释。

/**
 * 一个线上下发的工具。
 *
 * **刻意没有 `kind` 字段，也没有 `run`**：不是「有个字段但我们记得检查它」，
 * 而是结构上就表达不出「这是个变更工具」「这个在本地执行」。
 * 于是「wire 工具一律只读、一律走服务端」由构造保证，不存在写错的路径。
 */
data class AgentRemoteToolSpec(
    val definition: AgentToolDefinition,
    /**
     * 仅用于下发裁剪（决定给模型看不看得到）。
     *
     * **不是权限门** —— 真正的门在服务端：`aiToolCatalog` 的身份判定 +
     * 各 service 自己的所有权校验。端上这份只是让模型少看见几个用不了的工具。
     */
    val identities: Set<String>,
) {
    val name: String get() = definition.name

    fun allowedFor(identity: String): Boolean = identity in identities
}

/**
 * DTO → spec。
 *
 * 类型名不认识就退回 [AgentParamType.STRING] —— 服务端将来加新类型时，
 * 老客户端应当**退化而不是整条丢弃**（丢弃 = 那个工具静默消失）。
 */
fun AiWireToolDto.toSpec(): AgentRemoteToolSpec {
    val parameters = LinkedHashMap<String, AgentToolParam>()
    val required = mutableListOf<String>()
    for (p in params) {
        parameters[p.name] = AgentToolParam(
            type = AgentParamType.entries.firstOrNull { it.wire == p.type } ?: AgentParamType.STRING,
            description = p.description,
            enumValues = p.enumValues,
            itemType = p.itemType?.let { t -> AgentParamType.entries.firstOrNull { it.wire == t } },
        )
        if (p.required) required += p.name
    }
    return AgentRemoteToolSpec(
        definition = AgentToolDefinition(
            name = name,
            description = description,
            parameters = parameters,
            required = required,
            // 数组顺序就是生成顺序 —— 部分模型对参数顺序敏感
            propertyOrdering = params.map { it.name },
        ),
        identities = identities.toSet(),
    )
}

/**
 * 钉死的 + 下发的 → 发给模型的工具集。
 *
 * 抽成纯函数是为了它能被直接测到 —— 规则只有两条，但都不能错：
 * 1. **重名时钉死的赢**：防服务端下发一个叫 `place_order` 的「只读」工具来遮蔽真的那个
 * 2. 按身份裁剪（端上这道只是让模型少看见几个用不了的，真正的门在服务端）
 *
 * ⚠️ 与另一端客户端的同名实现保持一致，改一边要同步另一边。
 */
fun mergeWireTools(
    pinned: List<AgentToolDefinition>,
    pinnedNames: Set<String>,
    wire: List<AgentRemoteToolSpec>,
    identity: String,
    onCollision: (String) -> Unit = {},
): List<AgentToolDefinition> = pinned + wire.mapNotNull {
    when {
        it.name in pinnedNames -> { onCollision(it.name); null }
        !it.allowedFor(identity) -> null
        else -> it.definition
    }
}

/**
 * 按身份缓存下发的工具集。
 *
 * **永远不阻塞对话**：取不到就用缓存，没缓存就只有钉死的那批 ——
 * AI 照常能聊，只是少几个工具。这是一条硬规则，别为了「保证拿到最新」去等。
 */
class AiWireToolCatalog(
    /** 落盘。抽成接口是为了 JVM 单测够得着 —— SharedPreferences 在 JVM 里跑不了 */
    private val store: KeyValueStore,
    private val scope: CoroutineScope,
    private val fetcher: suspend (String) -> AiToolSchemaResponse,
) {
    companion object {
        private const val TAG = "AiWireToolCatalog"

        /** 缓存有效期。取不到新的就继续用旧的，所以这个值偏保守没有代价。 */
        const val TTL_MS = 30 * 60 * 1000L

        private val json = Json { ignoreUnknownKeys = true }
    }

    private val cache = HashMap<String, List<AgentRemoteToolSpec>>()
    private val fetchedAt = HashMap<String, Long>()
    private val inFlight = HashMap<String, Deferred<List<AgentRemoteToolSpec>>>()
    private val lock = Mutex()

    /**
     * 当前身份可用的下发工具。
     *
     * 内存有且新鲜 → 直接给；否则拉一次（同身份并发只拉一次）；
     * 拉失败 → 落盘缓存 → 空。任何一步都不抛。
     */
    suspend fun tools(identity: String): List<AgentRemoteToolSpec> {
        val running = lock.withLock {
            val at = fetchedAt[identity]
            val hit = cache[identity]
            if (hit != null && at != null && System.currentTimeMillis() - at < TTL_MS) {
                return hit
            }
            inFlight[identity] ?: scope.async { fetch(identity) }.also { inFlight[identity] = it }
        }
        val result = running.await()
        lock.withLock { inFlight.remove(identity) }
        return result
    }

    private suspend fun fetch(identity: String): List<AgentRemoteToolSpec> = try {
        val resp = fetcher(identity)
        val specs = resp.tools.map { it.toSpec() }
        lock.withLock {
            cache[identity] = specs
            fetchedAt[identity] = System.currentTimeMillis()
        }
        store.put(key(identity), json.encodeToString(resp))
        specs
    } catch (t: Throwable) {
        Log.i(TAG, "下发工具拉取失败，回落缓存: ${t.message}")
        // 拉不到就用上一次的 —— 离线冷启动仍有工具可用
        val restored = restore(identity)
        // 刻意**不**更新 fetchedAt：下一次调用还会再试一次
        lock.withLock { cache[identity] = restored }
        restored
    }

    private fun restore(identity: String): List<AgentRemoteToolSpec> {
        val raw = store.get(key(identity)) ?: return emptyList()
        return runCatching {
            json.decodeFromString<AiToolSchemaResponse>(raw).tools.map { it.toSpec() }
        }.getOrElse { emptyList() }
    }

    private fun key(identity: String) = "schema:$identity"

    /** 登出 / 切账号时清 —— 下一个账号的身份集可能不同。 */
    suspend fun reset() {
        lock.withLock {
            cache.clear()
            fetchedAt.clear()
        }
        store.keys().forEach(store::remove)
    }

    /** 仅供测试注入，绕开网络。 */
    suspend fun seedForTesting(specs: List<AgentRemoteToolSpec>, identity: String) {
        lock.withLock {
            cache[identity] = specs
            fetchedAt[identity] = System.currentTimeMillis()
        }
    }
}
