package com.chunland.app.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chunland.app.core.ai.domain.AgentCard

// MARK: - 结构化卡片条（R3，对齐 iOS AgentCardStrip.swift）
//
// **模型只报 id，这里每个字段都来自服务端那一次查询的真实行。**
// 模型转述价格迟早会转错一次（尤其被上下文压缩之后），卡片不会 ——
// 它与同一次工具调用的文本同源，两者不会各说各话。
//
// 刻意不做点击进详情之外的交互：卡片是「权威呈现」，不是第二个操作入口。
// 加购下单仍走对话（有 HITL 确认），否则同一件事有两条语义不同的路径。

@Composable
fun AgentCardStrip(cards: List<AgentCard>, onOpenProduct: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cards.forEach { card ->
            when (card.kind) {
                "product" -> ProductCard(card, onOpenProduct)
                // 认不出的 kind 静默跳过 —— 服务端将来加新卡片类型时，
                // 老客户端不该显示一块空白或崩掉
                else -> Unit
            }
        }
    }
}

@Composable
private fun ProductCard(card: AgentCard, onOpenProduct: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = card.code != null) { card.code?.let(onOpenProduct) }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = card.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                card.name ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                card.price?.let {
                    Text(money(it), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                val original = card.originalPrice
                if (original != null && card.price != null && original > card.price) {
                    Text(
                        money(original),
                        style = MaterialTheme.typography.labelSmall,
                        textDecoration = TextDecoration.LineThrough,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    if (card.inStock == true) "有货" else "缺货",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 只做展示格式化 —— 一切金额计算在服务端，端上永不复算。 */
private fun money(v: Double): String =
    if (v == Math.floor(v)) "¥${v.toInt()}" else "¥$v"
