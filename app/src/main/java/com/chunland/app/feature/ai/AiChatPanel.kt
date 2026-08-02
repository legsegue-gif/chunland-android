package com.chunland.app.feature.ai

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.AiChatStore
import com.chunland.app.core.ai.AiToolName
import com.chunland.app.core.ai.StoredConversation
import com.chunland.app.core.ai.SystemAiProvider
import com.chunland.app.core.ai.SystemAiStatus
import com.chunland.app.feature.report.ReportSheet
import com.chunland.app.ui.MarkdownText
import com.chunland.app.ui.relativeTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AI 会话面板（tab 主会话与 scoped ✨ sheet 共用）：
 * 消息列表 + 欢迎语（View 装饰不进历史）+ 输入栏 + HITL 确认框 + 配置 CTA。
 * store 决定会话语义（tab 单例 / scoped 实例），面板只渲染状态。
 */
@Composable
fun AiChatPanel(
    graph: AppGraph,
    store: AiChatStore,
    title: String,
    snackbar: SnackbarHostState,
    showSettings: Boolean = true,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val messages by store.messages.collectAsState()
    val responding by store.responding.collectAsState()
    val pendingIntent by store.pendingIntent.collectAsState()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var showConfig by remember { mutableStateOf(false) }
    // isConfigured 非响应式（SharedPreferences），配置保存后手动翻新
    var configured by remember { mutableStateOf(store.isConfigured) }
    // AI 消息举报（长按气泡唤起）：snapshot = 被举报消息全文
    var reportSnapshot by remember { mutableStateOf<String?>(null) }
    // 会话抽屉（对齐 iOS ConversationDrawer）：历史列表/续聊/删除
    var showHistory by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    // 滚动仲裁 autoFollow（对齐 iOS AIChatView）：用户上滑看历史 → 停止跟底；
    // 滚回底部 / 自己发新消息 → 恢复跟随
    var autoFollow by remember { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    LaunchedEffect(atBottom) {
        if (atBottom) autoFollow = true
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && listState.canScrollForward) autoFollow = false
    }
    val lastLen = messages.lastOrNull()?.let { it.content.length + (it.reasoning?.length ?: 0) } ?: 0
    LaunchedEffect(messages.size, lastLen) {
        if (messages.isNotEmpty() && autoFollow) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    Column(modifier.fillMaxSize().padding(top = topPadding)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 历史抽屉只在 tab 主会话（对齐 iOS：scoped ✨ sheet 无抽屉 ——
            // 在 scoped 会话恢复旧会话，下轮落盘会给旧会话盖上本店 contextKey，劫持续聊槽）
            if (!store.isScoped) {
                IconButton(onClick = { showHistory = true }) {
                    Icon(Icons.Filled.History, contentDescription = "历史会话")
                }
            }
            IconButton(onClick = { store.reset() }, enabled = messages.isNotEmpty()) {
                Icon(Icons.Filled.Add, contentDescription = "新对话")
            }
            if (showSettings) {
                IconButton(onClick = { showConfig = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "AI 配置")
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                !configured -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("配置你的 AI 服务开始对话", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.padding(6.dp))
                    Text(
                        "支持任意 OpenAI 兼容接口。密钥只存本机，对话直连你配置的服务，不经过平台服务器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(10.dp))
                    Button(onClick = { showConfig = true }) { Text("配置 AI API") }
                }
                else -> {
                    // 工具结果（role=tool）只喂模型，不渲染 —— 提前过滤，避免 LazyColumn
                    // 的 spacedBy 给零高 item 留出空档
                    val visible = messages.filter { it.role != "tool" }
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (visible.isEmpty()) {
                            item(key = "welcome") {
                                // 欢迎语：View 装饰，不进历史（scoped 会话用上下文专属文案）
                                AssistantBubble(content = store.welcomeText, reasoning = null, note = null)
                            }
                        }
                        items(visible, key = { it.id }) { m ->
                            if (m.role == "user") {
                                UserBubble(m.content)
                            } else {
                                AssistantEntry(m, onReport = { snapshot -> reportSnapshot = snapshot })
                            }
                        }
                    }
                }
            }
        }

        // 输入栏
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp)
                .padding(bottom = bottomPadding + 8.dp)
                .imePadding(),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (configured) "想买点什么…" else "先配置 AI API") },
                enabled = configured,
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    if (responding) {
                        store.stop()
                    } else {
                        val err = store.send(input)
                        if (err == null) {
                            input = ""
                            autoFollow = true   // 自己发消息 → 恢复跟底
                        } else {
                            scope.launch { snackbar.showSnackbar(err) }
                        }
                    }
                },
                enabled = configured && (responding || input.isNotBlank()),
            ) {
                Icon(
                    if (responding) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (responding) "停止" else "发送",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showConfig) {
        AiConfigSheet(
            graph = graph,
            onDismiss = {
                showConfig = false
                configured = store.isConfigured
            },
        )
    }

    reportSnapshot?.let { snapshot ->
        ReportSheet(
            graph = graph,
            targetType = "ai_message",
            snapshot = snapshot,
            onDismiss = { reportSnapshot = null },
        )
    }

    if (showHistory) {
        ConversationDrawerSheet(
            graph = graph,
            onRestore = { conv ->
                store.restore(conv)
                showHistory = false
            },
            onDismiss = { showHistory = false },
        )
    }

    // HITL：AI 想执行变更类工具（加购/下单/改单/建方案/归类）时的确认框
    pendingIntent?.let { intent ->
        AlertDialog(
            onDismissRequest = { store.cancelIntent() },
            title = { Text("确认 AI 操作") },
            text = { Text(intent.summary) },
            confirmButton = {
                TextButton(onClick = { store.confirmIntent() }) { Text("确认执行") }
            },
            dismissButton = {
                TextButton(onClick = { store.cancelIntent() }) { Text("取消") }
            },
        )
    }
}

/** assistant 消息：气泡 + 工具调用指示行（有 toolCalls 且无文字时只显示指示行） */
@Composable
private fun AssistantEntry(m: AiChatStore.AiMessage, onReport: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val hasBubbleContent = m.content.isNotEmpty() || !m.reasoning.isNullOrEmpty() || m.note != null
        if (hasBubbleContent || m.toolCalls == null) {
            AssistantBubble(
                m.content, m.reasoning, m.note,
                onLongPress = if (m.content.isNotEmpty()) {
                    { onReport(m.content) }
                } else null,
            )
        }
        m.toolCalls?.let { calls ->
            Text(
                "⚙︎ " + calls.joinToString("、") { AiToolName.fromWire(it.function.name)?.friendlyName ?: it.function.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun UserBubble(content: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    content: String,
    reasoning: String?,
    note: String?,
    onLongPress: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                .let { base ->
                    // 长按举报 AI 消息（1.2 ①：targetType=ai_message，带内容快照）
                    if (onLongPress != null) {
                        base.combinedClickable(onClick = {}, onLongClick = onLongPress)
                    } else base
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!reasoning.isNullOrEmpty()) {
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (content.isNotEmpty()) Spacer(Modifier.padding(3.dp))
            }
            if (content.isNotEmpty()) {
                // AI 回复走轻量块级 Markdown（对齐 iOS MarkdownText）
                MarkdownText(content)
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 会话抽屉（对齐 iOS ConversationDrawer）：属主的历史会话列表，点选续聊、可删除。
 * 数据每次打开重读（会话量小；持久化失败的坏文件已在 store 层跳过）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationDrawerSheet(
    graph: AppGraph,
    onRestore: (StoredConversation) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<StoredConversation>?>(null) }

    LaunchedEffect(Unit) {
        items = graph.conversationStore.list(graph.aiOwner())
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "历史会话",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            val list = items
            when {
                list == null -> Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                list.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有历史会话，聊过之后会自动保存在这里",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(list, key = { it.id }) { conv ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRestore(conv) }
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    conv.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    relativeTime(conv.updatedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                // 乐观移除 + 删盘（会话删除无需确认：单条、可重聊）
                                items = list.filterNot { it.id == conv.id }
                                scope.launch { graph.conversationStore.delete(conv.id) }
                            }) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * AI 配置：来源勾选（系统提供 / 自定义）+ 自定义三字段，全存本机（AiSettings）。
 * 「系统提供」仅当本机 proxy 模块接入（SystemAiProvider.isIntegrated）时显示，
 * 模块未接入时整页退化为纯自定义表单（对齐 iOS AISetupSheet）。选择态即生效态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiConfigSheet(graph: AppGraph, onDismiss: () -> Unit) {
    val settings = graph.aiSettings
    val integrated = SystemAiProvider.isIntegrated
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    // 当前生效来源（null = 尚未配置）；自定义未配置时点击其行仅展开表单引导填写
    var selection by remember {
        mutableStateOf(
            when {
                settings.useSystem && integrated -> "system"
                settings.isConfigured -> "custom"
                else -> null
            },
        )
    }
    var customExpanded by remember { mutableStateOf(selection == "custom") }

    val fieldsComplete = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
    val showCustomFields = !integrated || selection == "custom" || customExpanded

    fun saveCustom() {
        if (!fieldsComplete) return
        settings.useSystem = false
        settings.baseUrl = baseUrl.trim()
        settings.model = model.trim()
        settings.apiKey = apiKey.trim()
        selection = "custom"
        // 模块未接入时页面没有来源列表（保存后无勾选移动可作反馈），直接关闭
        if (!integrated) onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AI 助手配置", style = MaterialTheme.typography.titleMedium)

            if (integrated) {
                // 来源勾选列表：勾在哪、哪个生效，点击立即生效
                Column(Modifier.fillMaxWidth()) {
                    SourceRow(
                        title = "系统提供",
                        selected = selection == "system",
                        subtitle = { ServiceStatusLabel() },
                        onClick = {
                            selection = "system"
                            customExpanded = false
                            settings.useSystem = true // 零配置预设，点击立即生效
                        },
                    )
                    SourceRow(
                        title = "自定义",
                        selected = selection == "custom",
                        subtitle = {
                            Text(
                                if (fieldsComplete) "OpenAI 兼容服务 · $model" else "OpenAI 兼容服务，需自行配置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {
                            customExpanded = true
                            if (fieldsComplete) saveCustom() // 配置完整 → 立即生效；未配置 → 展开引导
                        },
                    )
                    Text(
                        "系统 AI 由平台统一提供和维护，无需任何配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (showCustomFields) {
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL（OpenAI 兼容，如 https://api.example.com/v1）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("模型名") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "密钥只存本机；对话直连该服务，不经过平台服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        saveCustom()
                        if (integrated) onDismiss()
                    },
                    enabled = fieldsComplete,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (selection == "custom") "保存自定义配置" else "保存并启用自定义") }
            }
        }
    }
}

/** 来源勾选行（iOS 设置惯用形态：勾在生效项上） */
@Composable
private fun SourceRow(
    title: String,
    selected: Boolean,
    subtitle: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle()
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 系统 AI 的服务状态标签：只显示状态、不暴露端口/本机实现 ——
 * 用户感知为「平台服务」的状态，而非手机上跑着什么（红线，对齐 iOS ServiceStatusLabel）。
 */
@Composable
private fun ServiceStatusLabel() {
    var status by remember { mutableStateOf(SystemAiProvider.status) }
    LaunchedEffect(Unit) {
        while (true) {
            status = SystemAiProvider.status
            delay(2000)
        }
    }
    val (color, text) = when (status) {
        SystemAiStatus.RUNNING -> Color(0xFF34A853) to "服务正常"
        SystemAiStatus.DISABLED -> MaterialTheme.colorScheme.outline to "服务维护中"
        SystemAiStatus.UNREACHABLE, SystemAiStatus.FAILED ->
            MaterialTheme.colorScheme.error to "服务暂不可用"
        else -> Color(0xFFF9A825) to "连接服务中…" // STARTING/WAITING_AUTH/STOPPED/null
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
