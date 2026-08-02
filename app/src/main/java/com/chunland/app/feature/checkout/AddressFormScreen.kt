package com.chunland.app.feature.checkout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.CreateAddressRequest
import com.chunland.app.data.model.Region
import kotlinx.coroutines.launch

/**
 * 新增地址：省/市/区三级级联（/regions 公开字典）+ 联系人/电话/详细地址。
 * address 全文 = 省市区名 + 详细地址；结构化 code 随行保存（区县 code 驱动距离定价）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressFormScreen(graph: AppGraph, onBack: () -> Unit, onSaved: () -> Unit) {
    BackHandler(onBack = onBack)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var isDefault by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

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

    fun save() {
        if (name.isBlank() || phone.isBlank() || detail.isBlank()) {
            scope.launch { snackbar.showSnackbar("请填写联系人、电话与详细地址") }
            return
        }
        val fullAddress = listOfNotNull(province?.name, city?.name, area?.name, detail.trim())
            .joinToString("")
        scope.launch {
            saving = true
            try {
                apiCall {
                    graph.addressApi.create(
                        CreateAddressRequest(
                            name = name.trim(),
                            phone = phone.trim(),
                            address = fullAddress,
                            isDefault = isDefault,
                            provinceCode = province?.code,
                            cityCode = city?.code,
                            areaCode = area?.code,
                            detail = detail.trim(),
                        )
                    )
                }
                onSaved()
            } catch (e: Exception) {
                snackbar.showSnackbar(e.userMessage)
            } finally {
                saving = false
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
                    Text("新增地址", style = MaterialTheme.typography.titleMedium)
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
                label = { Text("联系人") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = phone, onValueChange = { phone = it },
                label = { Text("手机号") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RegionField("省份", province, Modifier.weight(1f)) { openPicker(1) }
                RegionField("城市", city, Modifier.weight(1f), enabled = province != null) { openPicker(2) }
                RegionField("区县", area, Modifier.weight(1f), enabled = city != null) { openPicker(3) }
            }
            Text(
                "区县用于按距离计算代购费，建议选到区县级",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = detail, onValueChange = { detail = it },
                label = { Text("详细地址（街道/小区/门牌）") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isDefault, onCheckedChange = { isDefault = it })
                Text("设为默认地址", style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = ::save,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "保存中…" else "保存") }

            Spacer(Modifier.height(24.dp))
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
