package com.chunland.app.feature.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chunland.app.core.network.absoluteMediaUrl
import com.chunland.app.data.model.FeedItem
import com.chunland.app.data.model.FeedMedia
import com.chunland.app.ui.formatPrice
import com.chunland.app.ui.relativeTime

/**
 * 内容流卡片（发现流 + 店内动态面共用，对齐 iOS FeedCard）。
 * followed = null 时不显示关注按钮（店内动态面 / 商家贴无频道语境）。
 */
@Composable
internal fun FeedCard(
    item: FeedItem,
    followed: Boolean?,
    onClick: () -> Unit,
    onToggleFollow: () -> Unit = {},
) {
    Card(onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 来源区分：商家贴 / 频道贴（对齐 iOS storefront/paperplane 图标）
                Icon(
                    if (item.source == "merchant") Icons.Filled.Storefront else Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = if (item.source == "merchant") MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.authorName ?: item.authorHandle ?: "频道",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row {
                        item.authorHandle?.let {
                            Text(
                                "@$it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            relativeTime(item.publishedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (followed != null) {
                    TextButton(onClick = onToggleFollow) {
                        Text(if (followed) "已关注" else "+ 关注")
                    }
                }
            }

            item.text?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FeedCardMedia(item)

            // merchant 可购卡：价格 + 去购买 affordance（对齐 iOS）
            val meta = item.meta
            if (meta?.productCode != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    meta.price?.let { price ->
                        Text(
                            "¥${formatPrice(price)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "去购买",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 媒体区：单图按原比例、多图 2 列网格全展示、无图有视频显示播放占位（对齐 iOS mediaView）。 */
@Composable
private fun FeedCardMedia(item: FeedItem) {
    val images = item.media.filter { it.kind == "photo" || it.kind == "animation" }
    val hasVideo = item.media.any { it.kind == "video" }
    when {
        images.size == 1 -> {
            val m = images[0]
            AsyncImage(
                model = absoluteMediaUrl(m.url),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(mediaRatio(m))
                    .clip(MaterialTheme.shapes.medium),
            )
        }
        images.size > 1 -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                images.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        pair.forEach { m ->
                            AsyncImage(
                                model = absoluteMediaUrl(m.url),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(150.dp)
                                    .clip(MaterialTheme.shapes.small),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        hasVideo -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.PlayCircle,
                        contentDescription = "视频",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// 用 media 宽高算比例，限制极端值避免超长图占满屏（无宽高时回退 4:3，对齐 iOS）
private fun mediaRatio(m: FeedMedia): Float {
    val w = m.width
    val h = m.height
    if (w == null || h == null || w <= 0 || h <= 0) return 4f / 3f
    return (w.toFloat() / h).coerceIn(0.6f, 1.6f)
}
