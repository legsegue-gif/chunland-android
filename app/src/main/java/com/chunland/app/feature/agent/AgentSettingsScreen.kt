package com.chunland.app.feature.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.AgentProfile
import com.chunland.app.data.model.Region
import com.chunland.app.data.model.UpdateAgentProfileRequest
import kotlinx.coroutines.launch

/**
 * 代购设置：接单状态与资料（对齐 iOS AgentSettingsView）。
 * 接单开关（关 = 大厅不可抢单）+ 服务区县（空 = 全部区域）+ 自我介绍 + 只读统计。
 */
@Composable
fun AgentSettingsScreen(
    graph: AppGraph,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var profile by remember { mutableStateOf<AgentProfile?>(null) }
    var bio by remember { mutableStateOf("") }
    var isAvailable by remember { mutableStateOf(true) }
    var serviceAreas by remember { mutableStateOf(setOf<String>()) }
    var showAreaPicker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val p = apiCall { graph.agentProfileApi.profile() }
            profile = p
            bio = p.bio.orEmpty()
            isAvailable = p.isAvailable
            serviceAreas = p.serviceAreaCodes.toSet()
        } catch (e: Exception) {
            error = e.userMessage
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("代购设置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = !saving && profile != null,
                        onClick = {
                            scope.launch {
                                saving = true
                                error = null
                                try {
                                    val updated = apiCall {
                                        graph.agentProfileApi.updateProfile(
                                            UpdateAgentProfileRequest(
                                                bio = bio.trim().ifEmpty { null },
                                                isAvailable = isAvailable,
                                                serviceAreaCodes = serviceAreas.toList(),
                                            ),
                                        )
                                    }
                                    profile = updated
                                    serviceAreas = updated.serviceAreaCodes.toSet()
                                    snackbar.showSnackbar("已保存")
                                } catch (e: Exception) {
                                    error = e.userMessage
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    ) {
                        Text(if (saving) "保存中…" else "保存")
                    }
                }
            }
        },
    ) { padding ->
        val p = profile
        when {
            p == null && error == null -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            p == null -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("接受新订单", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Switch(checked = isAvailable, onCheckedChange = { isAvailable = it })
                        }
                        Text(
                            if (isAvailable) "当前可接单，新订单会出现在接单大厅"
                            else "已暂停接单，接单大厅将无法抢单",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("服务区县", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showAreaPicker = true }) {
                                Text(if (serviceAreas.isEmpty()) "全部区域" else "${serviceAreas.size} 个区县")
                            }
                        }
                        Text(
                            if (serviceAreas.isEmpty()) "未设置 = 接收全部区域的订单"
                            else "只接收所选区县的订单（无区县信息的老订单仍会推送）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("自我介绍", style = MaterialTheme.typography.bodyLarge)
                        OutlinedTextField(
                            value = bio,
                            onValueChange = { bio = it },
                            placeholder = { Text("写点什么让买家更了解你（可选）") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            Text("评分", modifier = Modifier.weight(1f))
                            Text("%.2f".format(p.rating), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        Row {
                            Text("累计订单", modifier = Modifier.weight(1f))
                            Text("${p.totalOrders}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showAreaPicker) {
        ServiceAreaPickerSheet(
            graph = graph,
            selected = serviceAreas,
            onToggle = { code ->
                serviceAreas = if (code in serviceAreas) serviceAreas - code else serviceAreas + code
            },
            onClear = { serviceAreas = emptySet() },
            onDismiss = { showAreaPicker = false },
        )
    }
}

/**
 * 服务区县多选：省→市 级联 + 区县 chips 多选（对齐 iOS ServiceAreaPicker 语义，
 * 匹配键始终是区县级 code）。选择只改本地集合，落库统一走「保存」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceAreaPickerSheet(
    graph: AppGraph,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var provinces by remember { mutableStateOf<List<Region>>(emptyList()) }
    var cities by remember { mutableStateOf<List<Region>>(emptyList()) }
    var areas by remember { mutableStateOf<List<Region>>(emptyList()) }
    var province by remember { mutableStateOf<Region?>(null) }
    var city by remember { mutableStateOf<Region?>(null) }

    LaunchedEffect(Unit) {
        runCatching { provinces = apiCall { graph.regionApi.children(null) } }
    }
    LaunchedEffect(province) {
        cities = emptyList()
        areas = emptyList()
        city = null
        province?.let { p ->
            runCatching { cities = apiCall { graph.regionApi.children(p.code) } }
        }
    }
    LaunchedEffect(city) {
        areas = emptyList()
        city?.let { c ->
            runCatching { areas = apiCall { graph.regionApi.children(c.code) } }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("服务区县", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    if (selected.isEmpty()) "全部区域" else "已选 ${selected.size} 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selected.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("清空") }
                }
            }
            Row(Modifier.heightIn(max = 360.dp)) {
                LazyColumn(Modifier.weight(1f)) {
                    items(provinces, key = { it.code }) { r ->
                        TextButton(onClick = { province = r }) {
                            Text(
                                r.name,
                                color = if (province?.code == r.code) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(cities, key = { it.code }) { r ->
                        TextButton(onClick = { city = r }) {
                            Text(
                                r.name,
                                color = if (city?.code == r.code) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                LazyColumn(Modifier.weight(1.2f)) {
                    items(areas, key = { it.code }) { r ->
                        FilterChip(
                            selected = r.code in selected,
                            onClick = { onToggle(r.code) },
                            label = { Text(r.name) },
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
