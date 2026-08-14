package com.chunland.app.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.AiContext
import com.chunland.app.core.ai.session.AiChatSession
import kotlinx.coroutines.launch

/**
 * 页面 ✨ 唤起的对话面板（对齐 iOS AgentChatSheet.swift）。
 *
 * 与旧 sheet 的关键差别：**不再有「切进切出」**。
 *
 * 旧的是一个全局 store 被反复改用途 —— 进来时记住 tab 会话、装载新上下文、
 * 出去时恢复。新的每个 contextKey 一个实例，打开就是打开、关闭就是关闭，
 * 没有需要「恢复」的东西。
 *
 * 红线兑现点：作用域（如进店 merchantId）由代码限定工具执行范围，
 * 不靠 seedNote 许愿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopedAiSheet(
    graph: AppGraph,
    context: AiContext,
    onDismiss: () -> Unit,
) {
    val runtime = graph.aiRuntime
    val scope = rememberCoroutineScope()
    var session by remember(context.contextKey ?: context.title) {
        mutableStateOf<AiChatSession?>(null)
    }

    LaunchedEffect(context.contextKey ?: context.title) {
        // 冷启动直接点 ✨（没先进过 AI tab）也要能用 —— 不能假设 tab 一定先被访问过
        runtime.bootstrap()
        if (runtime.isReady) {
            val target = runtime.sessions.session(context)
            target.open()
            session = target
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 没聊过的空会话直接删掉，不让抽屉堆一次性死会话。
            // 走 runtime 的 app 级作用域 —— 此刻本页面的 scope 已经取消了
            session?.let { runtime.closeSession(it) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "✨ 关于 ${context.title}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // 「新对话」= 归档语义：旧会话留在抽屉里当历史，只是不再被续聊命中
                IconButton(
                    onClick = { scope.launch { session?.restart() } },
                    enabled = session != null,
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "新对话")
                }
            }
            HorizontalDivider()

            Box(Modifier.weight(1f)) {
                val current = session
                when {
                    current != null -> AgentChatPanel(current, runtime.media)
                    runtime.bootstrapError != null -> Unavailable(runtime.bootstrapError!!)
                    else -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
            }
        }
    }
}

/** 进店 ✨（保留原调用面，委托通用 [ScopedAiSheet]） */
@Composable
fun StoreAiSheet(
    graph: AppGraph,
    merchantId: Int,
    merchantName: String,
    onDismiss: () -> Unit,
) {
    ScopedAiSheet(graph, AiContext.store(merchantId, merchantName), onDismiss)
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
