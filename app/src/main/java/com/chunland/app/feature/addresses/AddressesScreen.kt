package com.chunland.app.feature.addresses

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.apiCallUnit
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.Address
import com.chunland.app.data.model.UpdateAddressRequest
import kotlinx.coroutines.launch

/** 地址管理（我的 → 地址管理）。新增复用结算侧的 AddressFormScreen。 */
@Composable
fun AddressesScreen(graph: AppGraph, onBack: () -> Unit, onNewAddress: () -> Unit) {
    BackHandler(onBack = onBack)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var addresses by remember { mutableStateOf<List<Address>?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        try {
            addresses = apiCall { graph.addressApi.list() }
        } catch (e: Exception) {
            snackbar.showSnackbar(e.userMessage)
        }
    }

    // 进入/从新增表单返回都重拉
    LaunchedEffect(Unit) { reload() }

    fun setDefault(address: Address) {
        if (busy || address.isDefault) return
        scope.launch {
            busy = true
            try {
                apiCall { graph.addressApi.update(address.id, UpdateAddressRequest(isDefault = true)) }
                reload()
            } catch (e: Exception) {
                snackbar.showSnackbar(e.userMessage)
            } finally {
                busy = false
            }
        }
    }

    fun remove(address: Address) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                apiCallUnit { graph.addressApi.delete(address.id) }
                reload()
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
                    Text("地址管理", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onNewAddress) { Text("+ 新增") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = addresses
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有收货地址", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.padding(6.dp))
                    Button(onClick = onNewAddress) { Text("新增地址") }
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { address ->
                    AddressCard(
                        address = address,
                        busy = busy,
                        onSetDefault = { setDefault(address) },
                        onDelete = { remove(address) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressCard(
    address: Address,
    busy: Boolean,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(address.name, fontWeight = FontWeight.Bold)
                if (address.isDefault) {
                    Spacer(Modifier.padding(3.dp))
                    Text(
                        "默认",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(address.phone, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                address.address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!address.isDefault) {
                    TextButton(onClick = onSetDefault, enabled = !busy) { Text("设为默认") }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
