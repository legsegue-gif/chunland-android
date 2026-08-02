package com.chunland.app.feature.agent

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.Settlement
import com.chunland.app.data.model.SettlementSummary
import com.chunland.app.data.model.settlementStatusLabel
import com.chunland.app.ui.formatPrice

/**
 * 代购人「待结算 / 收益」（只读，对齐 iOS SettlementsView）：
 * 余额聚合 + 应结算明细。阶段0 仅记账，实际打款另行处理（NullPayout 占位）。
 */
@Composable
fun SettlementsScreen(graph: AppGraph, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var summary by remember { mutableStateOf<SettlementSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            summary = apiCall { graph.agentProfileApi.settlements() }
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
                    Text("待结算 / 收益", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        val s = summary
        when {
            s == null && error == null -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "balance") { BalanceCard(s!!) }
                item(key = "note") {
                    Text(
                        "订单完成后生成应结算账（货款返还 + 代购费）。当前为记账阶段，实际打款另行处理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s!!.items.isEmpty()) {
                    item(key = "empty") {
                        Text("暂无结算记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(s.items, key = { it.id }) { SettlementCard(it) }
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(s: SettlementSummary) {
    Card {
        Row(Modifier.fillMaxWidth().padding(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text("待结算", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "¥${formatPrice(s.pendingTotal)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("已结算", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "¥${formatPrice(s.paidTotal)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettlementCard(s: Settlement) {
    Card(Modifier.alpha(if (s.status == "VOID") 0.5f else 1f)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.orderNumber, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(
                    settlementStatusLabel(s.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (s.status) {
                        "PAID" -> MaterialTheme.colorScheme.primary
                        "VOID" -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "应得 ¥${formatPrice(s.netPayable)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "货款 ${formatPrice(s.itemsReimburse)} + 劳务 ${formatPrice(s.agentFee)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
