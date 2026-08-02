package com.chunland.app.feature.ai

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.chunland.app.core.AppGraph

/**
 * AI 助手 tab（对齐 iOS AIView 核心子集）：用户自配 OpenAI 兼容 endpoint 流式对话
 * + 工具循环（AiChatStore agent loop，mutation 走确认弹窗）；多模态/多会话后置。
 * 会话面板与进店 ✨ sheet 共用 AiChatPanel，本文件只接 tab 语境（全局单例会话）。
 */
@Composable
fun AiChatScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
) {
    AiChatPanel(
        graph = graph,
        store = graph.aiChatStore,
        title = "AI 助手",
        snackbar = snackbar,
        topPadding = contentPadding.calculateTopPadding(),
        bottomPadding = contentPadding.calculateBottomPadding(),
    )
}
