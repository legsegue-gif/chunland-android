package com.chunland.app.feature.orders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.AiContext
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.OrderSummary
import com.chunland.app.data.model.orderStatusLabel
import com.chunland.app.feature.ai.ScopedAiSheet
import com.chunland.app.ui.formatPrice

@Composable
fun OrdersScreen(graph: AppGraph, onBack: () -> Unit, onOpenOrder: (Int) -> Unit) {
    BackHandler(onBack = onBack)

    var orders by remember { mutableStateOf<List<OrderSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var showAi by remember { mutableStateOf(false) }

    LaunchedEffect(status) {
        orders = null
        error = null
        try {
            orders = apiCall { graph.orderApi.list(status = status) }
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
                    Text("我的订单", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    // ✨ scoped AI（对齐 iOS AskAIButton .orders 上下文）
                    IconButton(onClick = { showAi = true }) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "AI 助手")
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                lazyRowItems(STATUS_FILTERS, key = { it.first ?: "all" }) { (code, label) ->
                    FilterChip(
                        selected = status == code,
                        onClick = { status = code },
                        label = { Text(label) },
                    )
                }
            }
            OrdersBody(orders, error, padding, onOpenOrder)
        }
    }

    if (showAi) {
        ScopedAiSheet(
            graph = graph,
            context = AiContext.orders(),
            onDismiss = { showAi = false },
        )
    }
}

/** 筛选项只是查询参数，状态语义仍归服务端状态机 */
private val STATUS_FILTERS: List<Pair<String?, String>> = listOf(
    null to "全部",
    "PENDING" to "待接单",
    "CLAIMED" to "待支付",
    "PAID" to "已支付",
    "PURCHASING" to "采购中",
    "DELIVERING" to "配送中",
    "DELIVERED" to "已送达",
    "COMPLETED" to "已完成",
    "CANCELLED" to "已取消",
)

@Composable
private fun OrdersBody(
    orders: List<OrderSummary>?,
    error: String?,
    padding: PaddingValues,
    onOpenOrder: (Int) -> Unit,
) {
    // 顶部 inset 已由外层 Column 消费，这里只吃 bottom
    val bottom = padding.calculateBottomPadding()
    val list = orders
    Box(Modifier.fillMaxSize()) {
        when {
            list == null && error == null -> Box(
                Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null -> Box(
                Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center,
            ) { Text(error, color = MaterialTheme.colorScheme.error) }
            list!!.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center,
            ) { Text("还没有订单", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = 8.dp,
                    bottom = bottom + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { order ->
                    OrderCard(order) { onOpenOrder(order.id) }
                }
            }
        }
    }
}

@Composable
internal fun OrderCard(order: OrderSummary, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.merchantName ?: "订单",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    orderStatusLabel(order.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            order.firstProductName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.orderNumber,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                order.itemCount?.let {
                    Text("共 $it 件  ", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "¥${formatPrice(order.totalAmount)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
