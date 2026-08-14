package com.chunland.app.feature.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chunland.app.core.ai.session.ChatToolBlock

/**
 * 工具调用块（对齐 iOS AgentToolBlockView.swift）。
 *
 * 旧实现只有一行「正在搜索商品…」的灰字指示器，调用完就消失，
 * 用户既不知道 AI 查了什么，也无从判断结果对不对。
 *
 * 三点改进：
 * - 显示模型自述的 tool_title（「在本店找 100 元内的坚果」而不是「搜索商品」）
 * - 保留终态（成功/失败/取消都留在对话里，可回看）
 * - 可展开看结果摘要 —— 默认折叠，不打断阅读
 */
@Composable
fun AgentToolBlock(block: ChatToolBlock, modifier: Modifier = Modifier) {
    val hasDetail = !block.resultPreview.isNullOrEmpty()

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            // 没有可展开内容时不接受点击，避免给出「能点」的错误暗示
            .then(
                if (hasDetail) Modifier.clickable { block.isExpanded = !block.isExpanded }
                else Modifier
            )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusIcon(block.status)
            Text(
                text = block.headline,
                style = MaterialTheme.typography.bodySmall,
                color = if (block.status == ChatToolBlock.Status.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hasDetail) {
                Icon(
                    imageVector = if (block.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }

        AnimatedVisibility(visible = block.isExpanded && hasDetail) {
            Column {
                HorizontalDivider(Modifier.padding(start = 34.dp))
                Text(
                    text = block.resultPreview.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(status: ChatToolBlock.Status) {
    when (status) {
        ChatToolBlock.Status.RUNNING ->
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        ChatToolBlock.Status.SUCCESS ->
            Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = Color(0xFF34C759))
        ChatToolBlock.Status.FAILED ->
            Icon(Icons.Default.Error, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        ChatToolBlock.Status.CANCELLED ->
            Icon(Icons.Default.RemoveCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

/**
 * 思考块。
 *
 * 部分模型会输出推理过程。默认折叠 —— 它对结果没有约束力，
 * 展开只是给好奇的用户看，不该占据正文的注意力。
 */
@Composable
fun AgentThinkingBlock(text: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (expanded) "收起思考过程" else "查看思考过程",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(10.dp),
            )
        }
    }
}
