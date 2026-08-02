package com.chunland.app.feature.merchant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
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
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.MerchantOrderDetail
import com.chunland.app.data.model.MerchantOrderSummary
import com.chunland.app.data.model.orderStatusLabel
import com.chunland.app.ui.formatPrice

/**
 * 商家「订单」tab（纯只读）：自家订单列表 + 明细。
 * 隐私边界由服务端保证 —— 不下发买家地址/联系方式；金额只有货值（平台费/代购费与商家无关）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MerchantOrdersScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
) {
    var orders by remember { mutableStateOf<List<MerchantOrderSummary>?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(status) {
        orders = null
        error = null
        try {
            orders = apiCall { graph.merchantConsoleApi.orders(status = status) }.items
        } catch (e: Exception) {
            error = e.userMessage
        }
    }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Text(
            "店铺订单",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(STATUS_FILTERS, key = { it.first ?: "all" }) { (code, label) ->
                FilterChip(
                    selected = status == code,
                    onClick = { status = code },
                    label = { Text(label) },
                )
            }
        }

        val bottom = contentPadding.calculateBottomPadding()
        val list = orders
        when {
            list == null && error == null -> Box(
                Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null -> Box(
                Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center,
            ) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            list!!.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center,
            ) { Text("暂无订单", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { o ->
                    MerchantOrderCard(o) { detailId = o.id }
                }
            }
        }
    }

    detailId?.let { id ->
        MerchantOrderDetailSheet(graph, id, onDismiss = { detailId = null })
    }
}

@Composable
private fun MerchantOrderCard(o: MerchantOrderSummary, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.orderNumber, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(
                    orderStatusLabel(o.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    o.createdAt.take(10),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                o.itemCount?.let { Text("共 $it 件  ", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "¥${formatPrice(o.itemsTotal)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MerchantOrderDetailSheet(graph: AppGraph, orderId: Int, onDismiss: () -> Unit) {
    var detail by remember { mutableStateOf<MerchantOrderDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(orderId) {
        try {
            detail = apiCall { graph.merchantConsoleApi.order(orderId) }
        } catch (e: Exception) {
            error = e.userMessage
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val d = detail
            when {
                d != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("订单明细", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            orderStatusLabel(d.status),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        "${d.orderNumber} · ${d.createdAt.take(10)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    d.items.forEach { it ->
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(it.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                val sub = listOfNotNull(
                                    it.selectedSize?.let { s -> "尺码 $s" },
                                    "¥${formatPrice(it.unitPrice)} × ${it.quantity}",
                                ).joinToString(" · ")
                                Text(sub, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("¥${formatPrice(it.totalPrice)}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider()
                    Row {
                        Text("货值合计", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "¥${formatPrice(d.itemsTotal)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                else -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }
}

/** 商家视角状态筛选（与代购工作台同一组主链状态） */
private val STATUS_FILTERS: List<Pair<String?, String>> = listOf(
    null to "全部",
    "PENDING" to "待接单",
    "CLAIMED" to "待支付",
    "PAID" to "待采购",
    "PURCHASING" to "采购中",
    "DELIVERING" to "配送中",
    "DELIVERED" to "已送达",
    "COMPLETED" to "已完成",
)
