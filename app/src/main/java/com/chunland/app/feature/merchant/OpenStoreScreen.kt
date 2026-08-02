package com.chunland.app.feature.merchant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.chunland.app.data.model.Region
import kotlinx.coroutines.launch

/**
 * 开店（自建商家）：店铺名 + 发货地区县（驱动距离定价，可选）。
 * 提交 = 建店 + 授予商家身份 + 重签 token（AuthManager.openMerchantStore），
 * 成功即 activeIdentity 切 merchant，pop 回 tabs 自动换商家布局。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenStoreScreen(graph: AppGraph, onBack: () -> Unit, onOpened: () -> Unit) {
    BackHandler(onBack = onBack)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var province by remember { mutableStateOf<Region?>(null) }
    var city by remember { mutableStateOf<Region?>(null) }
    var area by remember { mutableStateOf<Region?>(null) }
    // 当前打开的级联层：1省 2市 3区；0 关闭
    var pickerLevel by remember { mutableStateOf(0) }
    var options by remember { mutableStateOf<List<Region>>(emptyList()) }

    fun openPicker(level: Int) {
        val parent = when (level) {
            1 -> null
            2 -> province?.code ?: return
            else -> city?.code ?: return
        }
        scope.launch {
            try {
                options = apiCall { graph.regionApi.children(parent) }
                pickerLevel = level
            } catch (e: Exception) {
                snackbar.showSnackbar(e.userMessage)
            }
        }
    }

    fun submit() {
        val storeName = name.trim()
        if (storeName.isEmpty()) {
            scope.launch { snackbar.showSnackbar("请填写店铺名") }
            return
        }
        scope.launch {
            busy = true
            try {
                graph.authManager.openMerchantStore(storeName, area?.code)
                onOpened()
            } catch (e: Exception) {
                snackbar.showSnackbar(e.userMessage)
            } finally {
                busy = false
            }
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
                    Text("我要开店", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("店铺名（必填）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RegionField("省份", province, Modifier.weight(1f)) { openPicker(1) }
                RegionField("城市", city, Modifier.weight(1f), enabled = province != null) { openPicker(2) }
                RegionField("区县", area, Modifier.weight(1f), enabled = city != null) { openPicker(3) }
            }
            Text(
                "发货地用于计算配送距离费，可选、开店后可改；未设置时距离费按 0 计。\n" +
                    "平台不代收货款，交易结算方式不变。开店即获得商家身份，可随时在「我的」页切换身份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = ::submit,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "开店中…" else "开店") }
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
private fun RegionField(
    label: String,
    value: Region?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(value?.name ?: label, maxLines = 1)
    }
}
