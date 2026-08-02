package com.chunland.app.feature.stores

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.chunland.app.core.AppGraph
import com.chunland.app.core.StoreAnchorStore
import com.chunland.app.core.auth.AuthManager
import com.chunland.app.core.network.absoluteMediaUrl
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.api.AddressApi
import com.chunland.app.data.api.MerchantApi
import com.chunland.app.data.api.RegionApi
import com.chunland.app.data.model.Merchant
import com.chunland.app.data.model.Region
import kotlinx.coroutines.launch

// 对齐 iOS HomeView（店铺选择页）：商家卡片列表 → 点卡进店。
// 锚点：手选地点（StoreAnchorStore 持久化，省/市/区任一级）优先，未手选回退默认地址区县；
// server 回 distanceKm（与报价同口径，**只作展示**）并按距离升序。近/远分组见 NEARBY_RADIUS_KM。

/** 近店/远店折叠阈值（km），对齐 iOS MerchantStore.nearbyRadiusKm：覆盖邻市代购的经验值，
 *  超出的跨省远店默认折叠、不隐藏。后续可迁 system_configs 做成可配。 */
private const val NEARBY_RADIUS_KM = 150.0

class StoreListViewModel(
    private val api: MerchantApi,
    private val addressApi: AddressApi,
    private val authManager: AuthManager,
    private val anchorStore: StoreAnchorStore,
) : ViewModel() {
    var merchants by mutableStateOf<List<Merchant>>(emptyList())
        private set
    var anchorName by mutableStateOf<String?>(null)
        private set
    /** 当前 anchor 是否来自手选（区分「按所选地点」与「按默认地址」文案） */
    var anchorIsManual by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    init { load() }

    fun load() {
        viewModelScope.launch {
            loading = true
            try {
                val manual = anchorStore.code
                val anchor = manual ?: if (authManager.state.value.isLoggedIn) {
                    runCatching {
                        apiCall { addressApi.list() }.firstOrNull { it.isDefault }?.areaCode
                    }.getOrNull()
                } else null
                val resp = apiCall { api.list(anchor = anchor) }
                merchants = resp.items
                // 展示名以 server 回显为准，本地存的名字只作乐观兜底（对齐 iOS）
                anchorName = resp.anchor?.name ?: anchorStore.name.takeIf { manual != null }
                anchorIsManual = manual != null
                error = null
            } catch (e: Exception) {
                error = e.userMessage
            } finally {
                loading = false
            }
        }
    }

    fun setAnchor(code: String, name: String) {
        anchorStore.set(code, name)
        load()
    }

    fun clearAnchor() {
        anchorStore.clear()
        load()
    }
}

@Composable
fun StoreListScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    onOpenStore: (Merchant) -> Unit,
) {
    val vm: StoreListViewModel = viewModel {
        StoreListViewModel(graph.merchantApi, graph.addressApi, graph.authManager, graph.storeAnchor)
    }
    var showPicker by remember { mutableStateOf(false) }
    var farExpanded by rememberSaveable { mutableStateOf(false) }

    when {
        vm.merchants.isNotEmpty() -> {
            // 近/远分组：仅当 server 给了距离才分（未设 anchor 全走 near 原样展示）
            val near = vm.merchants.filter { (it.distanceKm ?: 0.0) <= NEARBY_RADIUS_KM }
            val far = vm.merchants.filter { (it.distanceKm ?: 0.0) > NEARBY_RADIUS_KM }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "anchor") {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    vm.anchorName == null -> "未设置地点，店铺不按距离排序"
                                    vm.anchorIsManual -> "📍 距离按所选地点（${vm.anchorName}）计算"
                                    else -> "📍 距离按默认地址（${vm.anchorName}）计算"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showPicker = true }) { Text("选地点") }
                        }
                        // 已有列表时的刷新/换锚点失败不能静默（列表与所选地点短暂不一致）
                        vm.error?.let {
                            Text(
                                "刷新失败：$it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                items(near, key = { it.id }) { merchant ->
                    StoreCard(merchant) { onOpenStore(merchant) }
                }
                if (far.isNotEmpty()) {
                    item(key = "far-header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { farExpanded = !farExpanded }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                "较远店铺（${far.size}）",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (farExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (farExpanded) {
                        items(far, key = { it.id }) { merchant ->
                            StoreCard(merchant) { onOpenStore(merchant) }
                        }
                    }
                }
            }
        }
        vm.loading -> Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(vm.error ?: "暂无店铺", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(6.dp))
                OutlinedButton(onClick = vm::load) { Text("重试") }
            }
        }
    }

    if (showPicker) {
        AnchorPickerSheet(
            regionApi = graph.regionApi,
            onDismiss = { showPicker = false },
            onApply = { code, name ->
                showPicker = false
                vm.setAnchor(code, name)
            },
            onClear = {
                showPicker = false
                vm.clearAnchor()
            },
        )
    }
}

/** 地点选择（省/市/区县任一级即可应用，对齐 iOS anchor 语义）；清除 = 回退默认地址 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnchorPickerSheet(
    regionApi: RegionApi,
    onDismiss: () -> Unit,
    onApply: (code: String, name: String) -> Unit,
    onClear: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var province by remember { mutableStateOf<Region?>(null) }
    var city by remember { mutableStateOf<Region?>(null) }
    var area by remember { mutableStateOf<Region?>(null) }
    var pickerLevel by remember { mutableStateOf(0) }
    var options by remember { mutableStateOf<List<Region>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun openPicker(level: Int) {
        val parent = when (level) {
            1 -> null
            2 -> province?.code ?: return
            else -> city?.code ?: return
        }
        scope.launch {
            try {
                options = apiCall { regionApi.children(parent) }
                pickerLevel = level
            } catch (e: Exception) {
                error = e.userMessage
            }
        }
    }

    val deepest = area ?: city ?: province

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("选择地点", style = MaterialTheme.typography.titleMedium)
            Text(
                "选到省 / 市 / 区县任一级即可，店铺按到该地点的距离排序。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openPicker(1) }, modifier = Modifier.weight(1f)) {
                    Text(province?.name ?: "省份", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { openPicker(2) }, enabled = province != null, modifier = Modifier.weight(1f),
                ) { Text(city?.name ?: "城市", maxLines = 1) }
                OutlinedButton(
                    onClick = { openPicker(3) }, enabled = city != null, modifier = Modifier.weight(1f),
                ) { Text(area?.name ?: "区县", maxLines = 1) }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val d = deepest ?: return@Button
                    val name = listOfNotNull(province?.name, city?.name, area?.name).joinToString(" ")
                    onApply(d.code, name.ifBlank { d.name })
                },
                enabled = deepest != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("按此地点排序") }
            TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Text("清除所选地点（回到默认地址）")
            }
        }
    }

    if (pickerLevel > 0) {
        ModalBottomSheet(onDismissRequest = { pickerLevel = 0 }) {
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(options, key = { it.code }) { region ->
                    Text(
                        region.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                when (pickerLevel) {
                                    1 -> { province = region; city = null; area = null }
                                    2 -> { city = region; area = null }
                                    else -> area = region
                                }
                                pickerLevel = 0
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StoreCard(merchant: Merchant, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            if (merchant.logoUrl != null) {
                AsyncImage(
                    model = absoluteMediaUrl(merchant.logoUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Storefront,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(merchant.name, style = MaterialTheme.typography.titleMedium)
                val area = listOfNotNull(merchant.cityName, merchant.areaName).joinToString(" · ")
                val distance = merchant.distanceKm?.let { "%.1fkm".format(it) }
                val subtitle = listOfNotNull(area.ifBlank { null }, distance).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        "📍 $subtitle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
