package com.chunland.app.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chunland.app.core.ai.loop.AgentMutationIntent

/**
 * 变更确认（批量，对齐 iOS MutationConfirmSheet.swift）。
 *
 * 旧实现一次只确认一个操作。轮次放开后（8 → 30），一个任务可能产生
 * 5-10 个变更 —— 商家「按吃穿住行分类」每个分类调一次归类工具 ——
 * 逐个弹窗用户会疯。
 *
 * 批量**不削弱安全性**：每一项的完整摘要都在，用户看到的信息量没变，
 * 只是把 N 次点击压成 1 次。取消 = 整批取消，不存在「同意一半」——
 * 半批执行会让数据处在模型没预期的中间状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MutationConfirmSheet(
    intents: List<AgentMutationIntent>,
    onDecision: (Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        // 下滑关闭会让循环一直等着 —— 当作取消处理，绝不静默悬挂
        onDismissRequest = { onDecision(false) },
        sheetState = sheetState,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                text = if (intents.size == 1) "AI 想执行以下操作" else "AI 想执行以下 ${intents.size} 项操作",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(intents, key = { _, it -> it.id }) { index, intent ->
                    IntentRow(index, intent, showIndex = intents.size > 1)
                }
            }

            Text(
                text = "确认后将立即执行。取消则全部不执行。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { onDecision(false) }, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
                Button(onClick = { onDecision(true) }, modifier = Modifier.weight(1f)) {
                    Text(if (intents.size == 1) "确认" else "全部确认")
                }
            }
        }
    }
}

@Composable
private fun IntentRow(index: Int, intent: AgentMutationIntent, showIndex: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showIndex) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(18.dp),
                )
            }
            Text(
                text = intent.summary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        if (intent.details.isNotEmpty()) {
            Column(
                Modifier.padding(start = if (showIndex) 26.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 键排序：同类操作每次展示顺序一致，用户扫一眼就能比对
                intent.details.keys.sorted().forEach { key ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            text = intent.details[key].orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
