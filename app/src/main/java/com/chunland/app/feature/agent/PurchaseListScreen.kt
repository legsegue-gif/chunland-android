package com.chunland.app.feature.agent

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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.absoluteMediaUrl
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.PurchaseList

/**
 * 合并采购清单（作业化，对齐 iOS PurchaseListView）：一次进店买 N 单的作业清单。
 * 待采购/采购中订单按商家分组、同商品（code+尺码）跨单聚合；勾选是本地作业标记
 * （买齐一件勾一件），不上服务端。缺小票的订单在组尾标出，点单号直达详情传票。
 */
@Composable
fun PurchaseListScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onOpenOrder: (Int) -> Unit,
) {
    BackHandler(onBack = onBack)

    var list by remember { mutableStateOf<PurchaseList?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 作业勾选：merchantId:code:size → 已拿到（进程内，离开页面即清）
    var checked by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        try {
            list = apiCall { graph.agentProfileApi.purchaseList() }
        } catch (e: Exception) {
            error = e.userMessage
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
                    Text("合并采购清单", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        val data = list
        when {
            data == null && error == null -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            data!!.groups.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "没有待采购的订单\n接单支付后，这里会按店铺汇总要买的商品",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            ) {
                items(data.groups, key = { it.merchantId }) { group ->
                    GroupCard(
                        group = group,
                        checked = checked,
                        onToggle = { key ->
                            checked = if (key in checked) checked - key else checked + key
                        },
                        onOpenOrder = onOpenOrder,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: PurchaseList.Group,
    checked: Set<String>,
    onToggle: (String) -> Unit,
    onOpenOrder: (Int) -> Unit,
) {
    fun keyOf(item: PurchaseList.Item) = "${group.merchantId}:${item.productCode}:${item.selectedSize ?: ""}"
    val done = group.items.count { keyOf(it) in checked }

    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.merchantName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "$done/${group.items.size} 项 · ${group.orders.size} 单",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (done == group.items.size && group.items.isNotEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            group.items.forEach { item ->
                val key = keyOf(item)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onToggle(key) },
                ) {
                    Checkbox(checked = key in checked, onCheckedChange = { onToggle(key) })
                    if (item.imageUrl != null) {
                        AsyncImage(
                            model = absoluteMediaUrl(item.imageUrl),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val parts = buildList {
                            item.selectedSize?.let { add("规格 $it") }
                            if (item.breakdown.size > 1) {
                                add(item.breakdown.joinToString(" ") { "${it.orderNumber.takeLast(4)}×${it.quantity}" })
                            }
                        }
                        if (parts.isNotEmpty()) {
                            Text(
                                parts.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "×${item.totalQuantity}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            HorizontalDivider()
            // 组内订单：缺小票的标警示，点击直达详情（传票在详情页）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.orders.forEach { ref ->
                    AssistChip(
                        onClick = { onOpenOrder(ref.id) },
                        label = { Text(ref.orderNumber.takeLast(6)) },
                        leadingIcon = if (!ref.hasReceipt) {
                            {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = "缺小票",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        } else null,
                    )
                }
            }
        }
    }
}
