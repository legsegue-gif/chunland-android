package com.chunland.app.feature.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonOff
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.apiCallUnit
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.BlockRequest
import com.chunland.app.data.model.BlockedUser
import kotlinx.coroutines.launch

/**
 * 黑名单管理（对齐 iOS BlockedUsersView，1.2 ③）。拉黑入口在会话页（随 IM 落地）；
 * 此处集中查看与解除。生效面在服务端：会话冻结 + 对方无法再接我的订单。
 */
@Composable
fun BlockedUsersScreen(
    graph: AppGraph,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val items = remember { mutableStateListOf<BlockedUser>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            val list = apiCall { graph.blockApi.list() }.items
            items.clear()
            items.addAll(list)
            error = null
        } catch (e: Exception) {
            error = e.userMessage
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

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
                    Text("黑名单", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && items.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "黑名单为空\n在会话中可拉黑对方；被拉黑的用户无法与你沟通或接你的订单",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(items, key = { it.userId }) { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Filled.PersonOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(item.displayName.ifEmpty { "用户 #${item.userId}" }, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            // 乐观移除，失败 reload 兜底（对齐 iOS）
                            val removed = item
                            items.removeAll { it.userId == removed.userId }
                            scope.launch {
                                try {
                                    apiCallUnit { graph.blockApi.unblock(BlockRequest(removed.userId)) }
                                } catch (e: Exception) {
                                    snackbar.showSnackbar(e.userMessage)
                                    load()
                                }
                            }
                        }) { Text("解除") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
