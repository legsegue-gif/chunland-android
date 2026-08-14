package com.chunland.app.feature.cart

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.AiContext
import com.chunland.app.core.network.absoluteMediaUrl
import com.chunland.app.data.model.CartItem
import com.chunland.app.feature.ai.ScopedAiSheet
import com.chunland.app.feature.checkout.CheckoutDraft
import com.chunland.app.ui.formatPrice

@Composable
fun CartScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
    onCheckout: () -> Unit,
) {
    val vm: CartViewModel = viewModel { CartViewModel(graph.cartApi, graph.configApi) }
    var pendingDelete by remember { mutableStateOf<CartItem?>(null) }
    var showAi by remember { mutableStateOf(false) }

    // 每次进入购物车 tab 都重新拉取（价格/库存可能已变）
    LaunchedEffect(Unit) { vm.reload() }
    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            snackbar.showSnackbar(it)
            vm.toast = null
        }
    }

    val cart = vm.cart
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        ) {
            Text("购物车", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            // ✨ 购物车助手：看车、凑单、直接下单
            IconButton(onClick = { showAi = true }) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = "AI 助手")
            }
        }

        val bottom = contentPadding.calculateBottomPadding()
        when {
        cart == null -> Box(Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center) {
            if (vm.loading) CircularProgressIndicator()
            else Text("加载失败，切换 tab 重试", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        cart.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center) {
            Text("购物车是空的，去店铺逛逛吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Column(Modifier.fillMaxSize().padding(bottom = bottom)) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                cart.items.groupBy { it.merchantId }.forEach { (merchantId, items) ->
                    item(key = "m$merchantId") {
                        Text(
                            items.first().merchantName,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(items, key = { "i${it.id}" }) { item ->
                        CartItemRow(
                            item = item,
                            checked = item.id in vm.selected,
                            onToggle = { vm.toggle(item) },
                            onQuantity = { vm.changeQuantity(item, it) },
                            onRemove = { pendingDelete = item },
                        )
                    }
                }
            }
            // 起送校验提示（起送金额来自服务端 config；服务端下单时仍兜底校验）
            val blocking = vm.blockingMerchants
            if (blocking.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "「${blocking.joinToString("、")}」未满起送，无法结算",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            Surface {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Checkbox(checked = vm.allSelected, onCheckedChange = { vm.toggleSelectAll() })
                    Text("全选", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "合计 ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "¥" + "%.2f".format(vm.selectedItemsTotal),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            "已选 ${vm.selected.size} 件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        enabled = vm.canCheckout,
                        onClick = {
                            // 勾选结算：quote 与下单只带已选商品（对齐 iOS CheckoutView）
                            CheckoutDraft.productCodes = vm.selectedItems.map { it.productCode }.distinct()
                            onCheckout()
                        },
                    ) {
                        Text("结算(${vm.selected.size})")
                    }
                }
            }
        }
        }
    }

    if (showAi) {
        ScopedAiSheet(graph, AiContext.cart(), onDismiss = { showAi = false })
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${item.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    vm.remove(item)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CartItemRow(
    item: CartItem,
    checked: Boolean,
    onToggle: () -> Unit,
    onQuantity: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(end = 10.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = item.stockStatus != "outOfStock",
            )
            AsyncImage(
                model = absoluteMediaUrl(item.thumbnail),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.selectedSize?.let {
                    Text("规格：$it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.currentPrice?.let {
                        Text(
                            "¥${formatPrice(it)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (item.stockStatus == "outOfStock") {
                        Spacer(Modifier.width(6.dp))
                        Text("缺货", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onQuantity(item.quantity - 1) }, enabled = item.quantity > (item.minOrderQuantity ?: 1)) {
                        Icon(Icons.Filled.Remove, contentDescription = "减少")
                    }
                    Text("${item.quantity}", style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { onQuantity(item.quantity + 1) }, enabled = item.quantity < (item.maxOrderQuantity ?: Int.MAX_VALUE)) {
                        Icon(Icons.Filled.Add, contentDescription = "增加")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = "移除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
