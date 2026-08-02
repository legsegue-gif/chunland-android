package com.chunland.app.feature.agent

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.absoluteMediaUrl
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.api.OrderApi
import com.chunland.app.data.model.OrderSummary
import com.chunland.app.ui.formatPrice
import kotlinx.coroutines.launch

/** 接单大厅（agent）：服务区过滤 + 距离排序由服务端完成，端上只渲染。 */
class HallViewModel(private val api: OrderApi) : ViewModel() {
    var orders by mutableStateOf<List<OrderSummary>?>(null)
        private set
    var claiming by mutableStateOf(false)
        private set
    /** 下拉刷新指示（iOS 大厅是 SSE 实时流；Android 先给下拉刷新兜底，SSE 后置） */
    var refreshing by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)

    fun reload() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            try {
                orders = apiCall { api.list(scope = "hall", sort = "distance") }
            } catch (e: Exception) {
                toast = e.userMessage
            } finally {
                refreshing = false
            }
        }
    }

    fun claim(order: OrderSummary) {
        if (claiming) return
        viewModelScope.launch {
            claiming = true
            try {
                apiCall { api.claim(order.id) }
                orders = orders?.filterNot { it.id == order.id }
                toast = "接单成功，去工作台跟进"
            } catch (e: Exception) {
                toast = e.userMessage
            } finally {
                claiming = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HallScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
    onOpenOrder: (Int) -> Unit,
) {
    val vm: HallViewModel = viewModel { HallViewModel(graph.orderApi) }

    LaunchedEffect(Unit) { vm.reload() }
    LaunchedEffect(vm.toast) {
        vm.toast?.let { snackbar.showSnackbar(it); vm.toast = null }
    }

    val list = vm.orders
    if (list == null) {
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    // 下拉刷新：停留大厅期间新单不推送（无 SSE），下拉是唯一的手动刷新手段
    PullToRefreshBox(
        isRefreshing = vm.refreshing,
        onRefresh = vm::reload,
        modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()),
    ) {
        if (list.isEmpty()) {
            Box(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无可接订单，下拉刷新看看…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { order ->
                    HallCard(
                        order = order,
                        claiming = vm.claiming,
                        onClick = { onOpenOrder(order.id) },
                        onClaim = { vm.claim(order) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HallCard(
    order: OrderSummary,
    claiming: Boolean,
    onClick: () -> Unit,
    onClaim: () -> Unit,
) {
    Card(onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (order.firstThumbnail != null) {
                    AsyncImage(
                        model = absoluteMediaUrl(order.firstThumbnail),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        order.firstProductName ?: (order.merchantName ?: "订单"),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row {
                        order.merchantName?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        order.distanceKm?.let {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "📍 %.1fkm".format(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (order.onTheWay == true) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "顺路",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                order.itemCount?.let {
                    Text("共 $it 件", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    "¥${formatPrice(order.totalAmount)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "（含代购费 ¥${formatPrice(order.agentFee)}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Button(onClick = onClaim, enabled = !claiming) { Text("接单") }
            }
        }
    }
}
