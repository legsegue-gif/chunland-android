package com.chunland.app.feature.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.AiContext

/**
 * 页面 ✨ scoped AI 会话 sheet（对齐 iOS AIChatSheet）：任何页面唤起 AI 只产出
 * 一个 AiContext（页面与 AI 的唯一耦合面）。会话实例经 AppGraph.scopedAiStore 按
 * contextKey 复用 —— 关闭再开续聊（进程内）。
 * 红线兑现点：scope（如进店 merchantId）注进 AiToolScope 由代码限定，不靠 seedNote 许愿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopedAiSheet(
    graph: AppGraph,
    context: AiContext,
    onDismiss: () -> Unit,
) {
    val store = remember(context.contextKey ?: context.title) { graph.scopedAiStore(context) }
    val snackbar = remember { SnackbarHostState() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            AiChatPanel(
                graph = graph,
                store = store,
                title = "✨ ${context.title}",
                snackbar = snackbar,
                showSettings = false,
                bottomPadding = 16.dp,
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** 进店 ✨（保留原调用面，委托通用 ScopedAiSheet）。 */
@Composable
fun StoreAiSheet(
    graph: AppGraph,
    merchantId: Int,
    merchantName: String,
    onDismiss: () -> Unit,
) {
    ScopedAiSheet(graph, AiContext.store(merchantId, merchantName), onDismiss)
}
