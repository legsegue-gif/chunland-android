package com.chunland.app.feature.follows

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.absoluteMediaUrl
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.FollowDetailItem
import com.chunland.app.ui.formatPrice
import kotlinx.coroutines.launch

/**
 * 收藏与关注管理页（我的入口）。刻意不做 active 过滤：失效对象仍显示「已失效」+ 可移除
 * （与服务端/iOS 约定一致）。商品行点击直达详情。
 */
@Composable
fun FollowsManageScreen(graph: AppGraph, onBack: () -> Unit, onOpenProduct: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<FollowDetailItem>?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            items = apiCall { graph.feedApi.followsDetail() }.items
        } catch (e: Exception) {
            snackbar.showSnackbar(e.userMessage)
        }
    }

    fun remove(item: FollowDetailItem) {
        if (busy) return
        scope.launch {
            busy = true
            // 对齐 iOS FollowsManageView 的两道防护：先确保关注集已加载，再确认确实在关注中
            // 才 toggle —— 否则 keys 为空（agent/merchant 布局无 FeedScreen 加载）时 toggle
            // 方向反转，「移除」误发成 follow
            graph.followStore.loadIfNeeded()
            val key = "${item.targetType}:${item.targetKey}"
            val err = if (key in graph.followStore.keys.value) {
                graph.followStore.toggle(item.targetType, item.targetKey)
            } else {
                null   // 服务端已不在关注（列表过期）→ 直接从 UI 移除即可
            }
            if (err == null) {
                items = items?.filterNot {
                    it.targetType == item.targetType && it.targetKey == item.targetKey
                }
            } else {
                snackbar.showSnackbar(err)
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            Surface {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("收藏与关注", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("还没有收藏或关注", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { "${it.targetType}:${it.targetKey}" }) { item ->
                    FollowRow(
                        item = item,
                        busy = busy,
                        onClick = {
                            if (item.targetType == "product") onOpenProduct(item.targetKey)
                        },
                        onRemove = { remove(item) },
                    )
                }
            }
        }
    }
}

private fun typeIcon(type: String): ImageVector = when (type) {
    "merchant" -> Icons.Filled.Storefront
    "product" -> Icons.Filled.ShoppingCart
    else -> Icons.Filled.Newspaper
}

private fun typeLabel(type: String): String = when (type) {
    "merchant" -> "店铺"
    "product" -> "商品"
    else -> "频道"
}

@Composable
private fun FollowRow(
    item: FollowDetailItem,
    busy: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
        ) {
            if (item.thumbnail != null) {
                AsyncImage(
                    model = absoluteMediaUrl(item.thumbnail),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(
                    typeIcon(item.targetType),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name ?: "（已失效对象）",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (item.available == false) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "已失效",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Row {
                    Text(
                        typeLabel(item.targetType),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.handle?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "@$it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item.price?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "¥${formatPrice(it)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            TextButton(onClick = onRemove, enabled = !busy) { Text("移除") }
        }
    }
}
