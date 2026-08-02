package com.chunland.app.feature.checkout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chunland.app.core.AppGraph
import com.chunland.app.data.model.Address
import com.chunland.app.data.model.QuoteGroup
import com.chunland.app.ui.formatPrice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onNewAddress: () -> Unit,
    onPlaced: () -> Unit,
) {
    BackHandler(onBack = onBack)

    // CheckoutDraft 是进程内交接（CartScreen 结算前必然写入非 null）——为 null 只可能是
    // 进程死亡后导航栈恢复到本页。此时勾选丢失，继续会静默退化成整车结算 → 弹回购物车重来
    if (CheckoutDraft.productCodes == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val vm: CheckoutViewModel = viewModel { CheckoutViewModel(graph.addressApi, graph.orderApi) }
    val snackbar = remember { SnackbarHostState() }
    var showAddressSheet by remember { mutableStateOf(false) }

    // 进入/从地址表单返回时都会重新组合 → 重拉地址簿
    LaunchedEffect(Unit) { vm.loadAddresses() }
    LaunchedEffect(vm.toast) {
        vm.toast?.let { snackbar.showSnackbar(it); vm.toast = null }
    }

    // 下单成功页（对齐 iOS orderSuccess：给明确的成交反馈，不直接跳走）
    vm.placed?.let { batch ->
        BackHandler { onPlaced() }
        Scaffold { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("下单成功", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "共 ${batch.orderCount} 单 · 合计 ¥${batch.grandTotal}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "等待代购人接单后即可支付",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onPlaced, modifier = Modifier.fillMaxWidth()) {
                    Text("查看订单")
                }
            }
        }
        return
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
                    Text("结算", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    val quote = vm.quote
                    if (quote != null) {
                        Text("合计 ", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "¥${formatPrice(quote.grandTotal)}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = vm::submit,
                        enabled = vm.selected != null && vm.quote != null && !vm.quoting &&
                            !vm.submitting && vm.quote?.groups?.all { it.meetsMinOrder } == true,
                    ) {
                        Text(if (vm.submitting) "提交中…" else "提交订单")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 收货地址
            Card(onClick = { showAddressSheet = true }) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    val selected = vm.selected
                    if (selected == null) {
                        Text("请选择收货地址", color = MaterialTheme.colorScheme.primary)
                    } else {
                        Row {
                            Text(selected.name, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(0.dp).weight(1f))
                            Text(selected.phone, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(selected.address, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 报价分组（服务端 quote，含距离代购费）
            when {
                vm.quoting -> CircularProgressIndicator(Modifier.padding(8.dp))
                vm.quote != null -> vm.quote!!.groups.forEach { group -> QuoteGroupCard(group) }
                vm.selected == null -> Text(
                    "选择地址后展示报价（代购费按距离计算）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAddressSheet) {
        ModalBottomSheet(onDismissRequest = { showAddressSheet = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    "选择收货地址",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                LazyColumn {
                    items(vm.addresses, key = { it.id }) { address ->
                        AddressRow(
                            address = address,
                            selected = vm.selected?.id == address.id,
                            onClick = {
                                vm.select(address)
                                showAddressSheet = false
                            },
                        )
                    }
                }
                TextButton(
                    onClick = {
                        showAddressSheet = false
                        onNewAddress()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) { Text("+ 新增地址") }
            }
        }
    }
}

@Composable
private fun AddressRow(address: Address, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row {
                Text(address.name, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(address.phone, style = MaterialTheme.typography.bodySmall)
            }
            Text(address.address, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuoteGroupCard(group: QuoteGroup) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(group.merchantName, style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()
            FeeRow("商品合计", group.itemsTotal)
            FeeRow("平台服务费（${formatPrice(group.platformFeeRate * 100)}%）", group.platformFee)
            FeeRow("代购费（含距离）", group.agentFee)
            HorizontalDivider()
            FeeRow("小计", group.totalAmount, bold = true)
            if (!group.meetsMinOrder) {
                Text(
                    "未达该店起送金额 ¥${formatPrice(group.minOrderAmount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun FeeRow(label: String, amount: Double, bold: Boolean = false) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            "¥${formatPrice(amount)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
