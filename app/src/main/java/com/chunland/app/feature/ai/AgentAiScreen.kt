package com.chunland.app.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.AiContext
import com.chunland.app.core.ai.session.AiChatSession
import com.chunland.app.core.ai.storage.SessionRepo
import kotlinx.coroutines.launch

/** 助手 tab 主对话的续聊键（双端一致；iOS 同名常量在 AgentAIView） */
private const val MAIN_CONTEXT_KEY = "main"

/**
 * AI 助手 tab（对齐 iOS AgentAIView.swift）。
 *
 * tab 的主对话 = 一条没有 contextKey 的会话（全局作用域、全量工具）。
 * 与页面 ✨ 的会话是**平级的两个实例**，不存在谁挂起谁的关系 ——
 * 这正是多实例模型消掉的那类状态。
 */
@Composable
fun AgentAiScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    @Suppress("UNUSED_PARAMETER") snackbar: SnackbarHostState,
    /** 点结构化卡片进商品详情（R3）。AI tab 是全屏页，可以安全地往里推一层。 */
    onOpenProduct: (String) -> Unit = {},
) {
    val runtime = graph.aiRuntime
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<AiChatSession?>(null) }
    var showDrawer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    /**
     * tab 主对话：无作用域限定、无页面建议子集 —— 当前身份的全量工具。
     *
     * contextKey 固定为 `main` 且**不限续聊时间**：它就是「你正在进行的那个对话」，
     * 冷启动接着上次聊。没有这个 key 的话每次启动都会新建一条，用户只要开过
     * tab 又没说话，历史抽屉里就多一条空会话，且没有任何路径会删它。
     * 换新的是用户按「新对话」的显式动作（旧的摘掉 key 留在抽屉当历史）。
     */
    val mainContext = remember {
        AiContext(
            title = SessionRepo.UNTITLED,
            welcome = "你好！我可以帮你挑东西、下单、跟进订单。",
            contextKey = MAIN_CONTEXT_KEY,
        )
    }

    LaunchedEffect(Unit) {
        runtime.bootstrap()
        if (runtime.isReady && session == null) {
            val target = runtime.sessions.session(mainContext)
            target.open(resumeWithinMs = null)
            session = target
        }
    }

    // 抽屉手势由 ModalNavigationDrawer 原生提供（跟手位移 + 松手吸附 + 遮罩点击关闭），
    // 手感与 iOS 侧自绘的 SideDrawer 对齐。
    // ⚠️ 只给 tab 主对话用，不给进店 ✨ 的 sheet 用：那层自己已有下拉关闭手势，
    // 再叠一个右滑会互相抢；且 scoped 会话本来就没有「历史列表」的概念。
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(showDrawer) {
        if (showDrawer) drawerState.open() else drawerState.close()
    }
    // 手势拖出来的开合要同步回状态位，否则按钮入口与手势会各说各话
    LaunchedEffect(drawerState.currentValue) {
        showDrawer = drawerState.currentValue == DrawerValue.Open
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 只在有会话时允许手势 —— 加载中/未就绪时拉出一个空列表没有意义
        gesturesEnabled = session != null,
        drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxWidth(0.82f)) {
                AgentConversationDrawer(
                    repo = SessionRepo(runtime.database),
                    ownerUserId = graph.aiOwner(),
                    padding = contentPadding,
                ) { sessionId ->
                    // 装载到当前实例：先存好正在聊的那条，再换过去
                    scope.launch { session?.load(sessionId) }
                    showDrawer = false
                }
            }
        },
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
                start = contentPadding.calculateStartPadding(LayoutDirection.Ltr),
                end = contentPadding.calculateEndPadding(LayoutDirection.Ltr),
            ),
    ) {
        TopBar(
            enabled = session != null,
            onHistory = { showDrawer = true },
            // 「新对话」= 归档语义：旧会话留在抽屉里当历史，只是不再被续聊命中
            onRestart = { scope.launch { session?.restart() } },
            onSettings = { showSettings = true },
        )
        HorizontalDivider()

        Box(Modifier.weight(1f)) {
            val current = session
            when {
                current != null -> AgentChatPanel(current, runtime.media, onOpenProduct)
                runtime.bootstrapError != null -> Unavailable(runtime.bootstrapError!!)
                else -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }
    }
    }

    if (showSettings) {
        DrawerSheet(onDismiss = { showSettings = false }) { padding ->
            AiProviderSettingsScreen(runtime.config, runtime.credentials, padding)
        }
    }
}

@Composable
private fun TopBar(
    enabled: Boolean,
    onHistory: () -> Unit,
    onRestart: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onHistory) { Icon(Icons.Filled.History, contentDescription = "历史对话") }
        Text(
            "AI 助手",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onRestart, enabled = enabled) {
            Icon(Icons.Filled.Edit, contentDescription = "新对话")
        }
        IconButton(onSettings) { Icon(Icons.Filled.Tune, contentDescription = "AI 配置") }
    }
}

@Composable
private fun Unavailable(message: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("AI 暂时不可用", style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 抽屉与配置页共用的承载 sheet —— 两者都是「盖住聊天、看完就走」的形态 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerSheet(
    onDismiss: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Box(Modifier.fillMaxWidth()) {
            content(PaddingValues(bottom = 24.dp))
        }
    }
}
