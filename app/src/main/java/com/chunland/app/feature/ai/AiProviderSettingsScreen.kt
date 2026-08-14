package com.chunland.app.feature.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.chunland.app.core.AppGraph
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chunland.app.core.ai.SystemAiProvider
import com.chunland.app.core.ai.SystemAiStatus
import com.chunland.app.core.ai.provider.ModelCatalog
import com.chunland.app.core.ai.provider.ModelEntry
import com.chunland.app.core.ai.provider.ModelGroup
import com.chunland.app.core.ai.provider.ProviderConfigStore
import com.chunland.app.core.ai.provider.ProviderCredentials
import com.chunland.app.core.ai.provider.ProviderInstance
import com.chunland.app.core.ai.provider.ProviderKind
import kotlinx.coroutines.launch

/**
 * AI 来源配置（对齐 iOS AIProviderSettingsView.swift）。
 *
 * 替换旧的「三个输入框（baseUrl/model/apiKey）+ 一个系统 AI 开关」。
 * 旧实现的问题不在界面，在它背后：同一份配置被多处各读一遍，
 * 改一个字段要改多个地方。现在全部走 [ProviderConfigStore]。
 *
 * 界面上多出来的是**降级链** —— 旧实现只能配一个来源，它挂了就没 AI 用；
 * 现在能配多个并排出优先级，前面的不可用时自动切下一个。
 */
@Composable
fun AiProviderSettingsScreen(
    config: ProviderConfigStore,
    credentials: ProviderCredentials,
    padding: PaddingValues,
) {
    val scope = rememberCoroutineScope()
    var instances by remember { mutableStateOf<List<ProviderInstance>>(emptyList()) }
    var entries by remember { mutableStateOf<List<ModelEntry>>(emptyList()) }
    var group by remember { mutableStateOf<ModelGroup?>(null) }
    var loading by remember { mutableStateOf(true) }
    // 新增与编辑**共用这一个状态**。Compose 不像 SwiftUI 那样「同一视图挂两个 sheet
    // 只生效一个」，但两份状态并存迟早会撞上「加着一半又点了编辑」的混合态。
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }

    suspend fun reload() {
        config.loadIfNeeded()
        instances = config.allInstances()
        entries = config.allEntries()
        group = config.defaultGroup()
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    if (loading) {
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { SectionHeader("使用顺序") }

        val members = group?.memberEntryIds.orEmpty()
        // 降级链里**当前启用**的成员（停用的不显示，但仍留在组数据里保位置）
        val visible = members.filter { id ->
            val entry = entries.firstOrNull { it.id == id } ?: return@filter false
            instances.firstOrNull { it.id == entry.instanceId }?.isEnabled == true
        }

        if (visible.isEmpty()) {
            item {
                // 配置还在、只是全被关了 —— 说清楚，否则看着像配置丢了
                val allDisabled = members.isNotEmpty()
                Text(
                    if (allDisabled) "所有来源都已停用，AI 无法使用" else "尚未配置可用来源",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (allDisabled) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        } else {
            itemsIndexed(visible, key = { _, id -> id }) { index, entryId ->
                val entry = entries.firstOrNull { it.id == entryId }
                val instance = entry?.let { e -> instances.firstOrNull { it.id == e.instanceId } }
                ChainRow(
                    index = index,
                    entry = entry,
                    instance = instance,
                    credentials = credentials,
                    canMoveUp = index > 0,
                    canMoveDown = index < visible.size - 1,
                    onMove = { offset ->
                        scope.launch {
                            group?.let { g ->
                                config.upsert(
                                    g.copy(memberEntryIds = reorderedMembers(g.memberEntryIds, visible, index, offset))
                                )
                                reload()
                            }
                        }
                    },
                )
            }
        }

        item {
            Text(
                "从上往下依次尝试。排在前面的不可用时（服务维护、请求过多、密钥失效），" +
                    "会自动切换到下一个，并在对话里告知你。已停用的来源不在这里显示。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            HorizontalDivider()
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已配置的来源", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { editTarget = EditTarget.New }) {
                    Icon(Icons.Default.Add, contentDescription = "添加来源")
                }
            }
        }

        items(instances, key = { it.id }) { instance ->
            SourceRow(instance, credentials) {
                // 系统 AI 没有可编辑的字段（地址与密钥都是运行时取的）
                if (instance.kind != ProviderKind.SYSTEM) editTarget = EditTarget.Existing(instance)
            }
        }
    }

    editTarget?.let { target ->
        ProviderEditSheet(
            config, credentials,
            instance = (target as? EditTarget.Existing)?.instance,
            onDismiss = { editTarget = null },
            onSaved = { scope.launch { reload() }; editTarget = null },
        )
    }
}

/** 表单的呈现目标（新增 or 编辑既有） */
private sealed interface EditTarget {
    data object New : EditTarget
    data class Existing(val instance: ProviderInstance) : EditTarget
}

/**
 * 上/下移一位后的完整成员列表。
 *
 * ⚠️ [index] 是**可见列表**（已滤掉停用项）的下标，而要改的是完整的 `memberEntryIds` ——
 * 直接拿它去 move 完整数组，中间夹着停用项时就会错位。
 * 故先在可见次序上移动，再按原槽位填回：停用项留在它原来的位置，
 * 重新启用时就回到原处（而不是被挤到链尾）。
 */
internal fun reorderedMembers(
    members: List<String>,
    visible: List<String>,
    index: Int,
    offset: Int,
): List<String> {
    val target = index + offset
    if (index !in visible.indices || target !in visible.indices) return members
    val moved = visible.toMutableList()
    moved.add(target, moved.removeAt(index))

    val visibleSet = visible.toSet()
    val iterator = moved.iterator()
    return members.map { id -> if (id in visibleSet) iterator.next() else id }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun ChainRow(
    index: Int,
    entry: ModelEntry?,
    instance: ProviderInstance?,
    credentials: ProviderCredentials,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
) {
    // 停用的来源根本不会出现在这个列表里（调用方已滤掉），不必再判
    val unusableReason = when {
        entry == null || instance == null -> "已删除"
        instance.kind == ProviderKind.UNSUPPORTED -> "不支持"
        instance.kind.usesStoredApiKey && credentials.apiKey(instance.id) == null -> "缺密钥"
        else -> null
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(entry?.displayName ?: "(已删除)", style = MaterialTheme.typography.bodyMedium)
            instance?.let {
                Text(it.label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
        // 不可用的条目留在链上但标出原因 —— 直接隐藏会让用户以为配置丢了，
        // 而它其实只是缺个密钥
        unusableReason?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary)
        }
        // 用上下按钮而不是拖拽：Compose 没有内置的 reorderable list，
        // 自己实现拖拽要处理手势冲突与滚动联动，按钮更简单可靠
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.ArrowUpward, "上移", Modifier.size(16.dp))
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.ArrowDownward, "下移", Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SourceRow(
    instance: ProviderInstance,
    credentials: ProviderCredentials,
    onClick: () -> Unit,
) {
    // 停用的来源已从「使用顺序」移除，这里是它唯一还看得见的地方 ——
    // 不标出来的话，用户不知道该去哪把它开回来
    val subtitle = if (!instance.isEnabled) "已停用" else when (instance.kind) {
        // 措辞保持服务级 —— 用户不需要知道它跑在哪、用什么端口
        ProviderKind.SYSTEM -> when (SystemAiProvider.status) {
            SystemAiStatus.RUNNING -> "服务正常"
            SystemAiStatus.DISABLED -> "维护中"
            SystemAiStatus.WAITING_AUTH -> "需要登录"
            null -> "不可用"
            else -> "准备中"
        }
        ProviderKind.OPENAI_COMPATIBLE -> {
            val host = instance.baseUrl?.substringAfter("://")?.substringBefore("/") ?: "未填写地址"
            if (credentials.apiKey(instance.id) != null) host else "$host · 未填 API Key"
        }
        ProviderKind.UNSUPPORTED -> "本版本不支持这种来源"
    }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (instance.kind == ProviderKind.SYSTEM) Icons.Default.AutoAwesome else Icons.Default.Dns,
            contentDescription = null,
            tint = if (instance.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Column(Modifier.weight(1f)) {
            Text(instance.label, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (instance.kind != ProviderKind.SYSTEM) {
            Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * 自定义来源的模型参数默认值 —— **不问用户**。
 *
 * 上下文长度取保守值：偏小只是提前整理历史，偏大则会撑爆窗口直接报错。
 * 识图默认开：用户主动发图就是期望模型能看，模型看不了会自己说明，
 * 比默默把图换成占位文案更诚实。
 */
private const val DEFAULT_CONTEXT_WINDOW = 32_000
private const val DEFAULT_SUPPORTS_VISION = true

/**
 * 添加 / 编辑自配来源。
 *
 * **只问用户答得上来的四项**（名称 / 接口地址 / API Key / 模型名）。
 * 上下文长度与识图能力刻意不做成表单项：配一个自定义端点的人，通常并不知道
 * 那个模型的窗口多大、支不支持视觉 —— 问了也只是逼他猜。前者只影响
 * 「何时自动整理历史」（填错不影响能否使用），后者失败时上游会明确报错或
 * 模型自己说看不了图，两种都是清楚的反馈。默认值见上面两个常量。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditSheet(
    config: ProviderConfigStore,
    credentials: ProviderCredentials,
    instance: ProviderInstance?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isNew = instance == null
    val stored = instance?.baseUrl.orEmpty()

    var label by remember { mutableStateOf(instance?.label.orEmpty()) }
    // 地址与开关是一对：库里存的是**最终地址**（provider 直接拿它拼 /chat/completions），
    // 编辑时按「是不是 /v1 结尾」反推出开关状态，好让用户看到的和他当初填的一致。
    var autoAppendV1 by remember { mutableStateOf(isNew || stored.endsWith("/v1")) }
    var baseUrl by remember {
        mutableStateOf(if (stored.endsWith("/v1")) stored.dropLast(3) else stored)
    }
    // 密钥回填真实值（默认仍是圆点遮罩，点眼睛才现形）。
    //
    // 这里推翻了原先「只写不读」的做法：不回显时，用户既没法核对自己填的对不对，
    // 也没法在换端点时把密钥抄出来 —— 而密钥本就存在这台设备上，本人看自己的密钥
    // 不构成新的泄露面（对齐 minis 的 Credential 段）。代价是「留空 = 不修改」
    // 这条语义不再成立，改为「留空保存 = 删除密钥」，下面的说明文案已写明。
    var apiKey by remember {
        mutableStateOf(instance?.let { credentials.apiKey(it.id) }.orEmpty())
    }
    /** 密钥是否明文显示。默认关 —— 只有用户主动点眼睛才现形 */
    var revealKey by remember { mutableStateOf(false) }
    // 模型名在 ModelEntry 上而不是 instance 上，得单独取 —— 漏了这一步的后果不只是
    // 「显示为空」：用户不重填就保存，会存进一个 modelId 为空的条目
    var modelId by remember {
        mutableStateOf(
            instance?.let { i -> config.allEntries().firstOrNull { it.instanceId == i.id }?.modelId }
                .orEmpty()
        )
    }
    /** 启用状态。关掉 = 保留配置但不参与降级链（不是删除） */
    var isEnabled by remember { mutableStateOf(instance?.isEnabled ?: true) }
    /** 从端点拉回来的模型列表（供选择，非必需 —— 手填一直可用） */
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    /** 表单里的地址 + 开关 → 真正存库的地址 */
    fun resolvedBaseUrl(): String {
        val url = baseUrl.trim().trimEnd('/')
        return if (autoAppendV1 && url.isNotEmpty() && !url.endsWith("/v1")) "$url/v1" else url
    }

    val canSave = label.isNotBlank() && baseUrl.isNotBlank() && modelId.isNotBlank() &&
        (!isNew || apiKey.isNotBlank())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (isNew) "添加来源" else "编辑来源", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                label, { label = it },
                label = { Text("名称") }, placeholder = { Text("我的 AI") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                baseUrl, { baseUrl = it },
                label = { Text("接口地址") },
                // 示例带完整协议头：Android 的 placeholder 不会被系统当链接染色，
                // 没有 iOS 那个「看着像已经填好了」的坑，写全反而更清楚
                placeholder = {
                    Text(if (autoAppendV1) "例：https://api.openai.com" else "例：https://api.openai.com/v1")
                },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动补 \"/v1\"", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(autoAppendV1, { autoAppendV1 = it })
            }
            Text(
                (if (autoAppendV1)
                    "接口地址填到主机名即可（要带 http:// 或 https://），\"/v1\" 会自动补上。\n"
                else
                    "接口地址要填完整，包含 \"/v1\" 这一级。\n") +
                    "密钥只存在这台设备上，不会离开本机。" +
                    if (isNew) "" else "清空并保存会删除已存的密钥。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            OutlinedTextField(
                apiKey, { apiKey = it },
                label = { Text("API Key") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                visualTransformation =
                    if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                // 密码键盘：密钥大小写敏感，不能让 IME 自动首字母大写或做联想更正
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { revealKey = !revealKey }) {
                        Icon(
                            if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (revealKey) "隐藏 API Key" else "显示 API Key",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    modelId, { modelId = it },
                    label = { Text("模型名") }, placeholder = { Text("gpt-4o") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                if (fetching) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(
                        onClick = {
                            fetching = true
                            modelFetchError = null
                            scope.launch {
                                // 用表单里的当前值拉，不是库里存的 —— 否则新增来源时（还没保存）
                                // 根本没法拉。编辑既有来源时表单已回填了已存的密钥
                                runCatching { ModelCatalog.fetch(resolvedBaseUrl(), apiKey) }
                                    .onSuccess { fetchedModels = it; showModelPicker = true }
                                    .onFailure {
                                        modelFetchError = it.message ?: "获取失败，请手动填写模型名"
                                    }
                                fetching = false
                            }
                        },
                        // 没有地址就拉不了；密钥可以留空（编辑时用已存的那把）
                        enabled = baseUrl.isNotBlank(),
                    ) { Text("获取") }
                }
            }
            // 拉取失败**绝不阻断保存** —— 手填那条路一直留着，这里只是提示
            modelFetchError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(isEnabled, { isEnabled = it })
            }
            Text(
                "关掉后这个来源会从「使用顺序」里移除，但配置和位置都保留 —— 重新启用就回到原来的位置。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            error?.let {
                Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isNew) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                config.deleteInstance(instance!!.id)
                                onSaved()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                Button(
                    onClick = {
                        val trimmedUrl = resolvedBaseUrl()
                        if (!trimmedUrl.startsWith("http")) {
                            error = "接口地址需要以 http:// 或 https:// 开头"
                            return@Button
                        }
                        saving = true
                        error = null
                        scope.launch {
                            runCatching {
                                val target = (instance ?: ProviderInstance(
                                    label = label.trim(),
                                    kind = ProviderKind.OPENAI_COMPATIBLE,
                                    baseUrl = trimmedUrl,
                                )).copy(
                                    label = label.trim(),
                                    baseUrl = trimmedUrl,
                                    isEnabled = isEnabled,
                                )
                                config.upsert(target)
                                // 无条件按表单值写 —— 空值在 setApiKey 里就是删除。
                                // 表单已回填已存密钥，所以「空」只可能是用户自己清掉的，那就该删
                                credentials.setApiKey(target.id, apiKey)

                                val entry = ModelEntry(
                                    instanceId = target.id,
                                    modelId = modelId.trim(),
                                    contextWindow = DEFAULT_CONTEXT_WINDOW,
                                    supportsVision = DEFAULT_SUPPORTS_VISION,
                                )
                                // 用 replace 而不是 upsert：改模型名会换掉条目 id，
                                // 直接 upsert 会留孤儿、降级链还指着旧 id（详见 store 内注释）
                                config.replaceEntry(entry, target.id)
                                // 新来源自动排进降级链尾部 —— 用户不必再去「使用顺序」手动加
                                config.ensureInDefaultGroup(entry.id)
                                onSaved()
                            }.onFailure {
                                error = it.message ?: "保存失败"
                                saving = false
                            }
                        }
                    },
                    enabled = canSave && !saving,
                    modifier = Modifier.weight(1f),
                ) { Text(if (saving) "保存中…" else "保存") }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            models = fetchedModels,
            current = modelId,
            onPick = { modelId = it; showModelPicker = false },
            onDismiss = { showModelPicker = false },
        )
    }
}

/** 拉回来的模型列表选择器（带搜索，当前值打勾） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    models: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    val filtered = remember(keyword, models) {
        val k = keyword.trim().lowercase()
        if (k.isEmpty()) models else models.filter { it.lowercase().contains(k) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("选择模型（${models.size}）", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                keyword, { keyword = it },
                label = { Text("搜索模型") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(filtered, key = { it }) { model ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(model) }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(model, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (model == current) {
                            Icon(Icons.Default.Check, contentDescription = "当前",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「我的」页唤起配置页用的承载 sheet。
 *
 * 与 AI tab 里的入口共用同一个 [AiProviderSettingsScreen] —— 配置只有一套，
 * 从哪进都是同一个页面，这正是这次把四处散读收口成一份配置的意义。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderSettingsSheet(
    graph: AppGraph,
    onDismiss: () -> Unit,
) {
    val runtime = graph.aiRuntime
    LaunchedEffect(Unit) { runtime.bootstrap() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        AiProviderSettingsScreen(
            config = runtime.config,
            credentials = runtime.credentials,
            padding = PaddingValues(bottom = 24.dp),
        )
    }
}
