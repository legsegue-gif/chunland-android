package com.chunland.app.feature.agent

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.chunland.app.core.ai.AiContext
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.AgentDashboard
import com.chunland.app.data.model.OrderSummary
import com.chunland.app.feature.ai.ScopedAiSheet
import com.chunland.app.feature.orders.OrderCard
import com.chunland.app.ui.formatPrice

/**
 * 代购工作台（对齐 iOS AgentWorkbenchView）：
 * dashboard 聚合卡（待办计数 + 收入）+ 合并采购清单入口 + 我接的单按状态跟进。
 */
@Composable
fun AgentWorkbenchScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
    onOpenOrder: (Int) -> Unit,
    onOpenPurchaseList: () -> Unit,
) {
    var orders by remember { mutableStateOf<List<OrderSummary>?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dashboard by remember { mutableStateOf<AgentDashboard?>(null) }
    var showAi by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // dashboard 失败不打断工作台（列表是主体）
        runCatching { dashboard = apiCall { graph.agentProfileApi.dashboard() } }
    }
    LaunchedEffect(status) {
        orders = null
        error = null
        try {
            orders = apiCall { graph.orderApi.list(status = status, scope = "mine") }
        } catch (e: Exception) {
            error = e.userMessage
        }
    }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        ) {
            Text("工作台", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            // ✨ 代购 AI 助理（对齐 iOS AIContext.workbench）
            IconButton(onClick = { showAi = true }) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = "AI 助理")
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            dashboard?.let { d ->
                item(key = "dashboard") { DashboardCard(d) }
            }
            item(key = "purchase-list") {
                Card(onClick = onOpenPurchaseList) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    ) {
                        Icon(
                            Icons.Filled.ShoppingBasket,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("合并采购清单", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            item(key = "filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(WORK_FILTERS, key = { it.first ?: "all" }) { (code, label) ->
                        FilterChip(
                            selected = status == code,
                            onClick = { status = code },
                            label = { Text(label) },
                        )
                    }
                }
            }
            val list = orders
            when {
                list == null && error == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> item(key = "error") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
                list!!.isEmpty() -> item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("还没有接单，去大厅看看", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> items(list, key = { it.id }) { order ->
                    OrderCard(order) { onOpenOrder(order.id) }
                }
            }
        }
    }

    if (showAi) {
        ScopedAiSheet(
            graph = graph,
            context = AiContext.workbench(),
            onDismiss = { showAi = false },
        )
    }
}

/** 待办计数 + 收入汇总（展示聚合；状态语义仍归服务端状态机） */
@Composable
private fun DashboardCard(d: AgentDashboard) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                CountCell("待采购", d.counts.paid, Modifier.weight(1f))
                CountCell("采购中", d.counts.purchasing, Modifier.weight(1f))
                CountCell("配送中", d.counts.delivering, Modifier.weight(1f))
                CountCell("待确认", d.counts.delivered, Modifier.weight(1f))
            }
            if (d.counts.purchasingNoReceipt > 0 || d.counts.purchasingPendingAdjustment > 0) {
                val notes = buildList {
                    if (d.counts.purchasingNoReceipt > 0) add("${d.counts.purchasingNoReceipt} 单缺小票")
                    if (d.counts.purchasingPendingAdjustment > 0) add("${d.counts.purchasingPendingAdjustment} 单改单待答复")
                }
                Text(
                    "⚠ " + notes.joinToString("、"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            HorizontalDivider()
            Row {
                EarningCell("今日", d.earnings.today, Modifier.weight(1f))
                EarningCell("本月", d.earnings.month, Modifier.weight(1f))
                EarningCell("待结算", d.earnings.pendingSettlement, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CountCell(label: String, count: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$count",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EarningCell(label: String, amount: Double, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "¥${formatPrice(amount)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 代购视角常用跟进状态 */
private val WORK_FILTERS: List<Pair<String?, String>> = listOf(
    null to "全部",
    "CLAIMED" to "待买家支付",
    "PAID" to "待采购",
    "PURCHASING" to "采购中",
    "DELIVERING" to "配送中",
    "DELIVERED" to "已送达",
    "COMPLETED" to "已完成",
)
