package com.chunland.app.feature.merchant

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.absoluteMediaUrl
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.CreatePostRequest
import com.chunland.app.data.model.MerchantPost
import com.chunland.app.data.model.MerchantProduct
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 店铺动态：商家自发图文进 feed_items，消费者在发现/关注流可见。
 * 发布 = 逐张传图拿 key → createPost(text, mediaKeys)；删除为软删（与 feed 全局约定一致）。
 * 挂商品（可购卡）后置。
 */
@Composable
fun MerchantPostsScreen(graph: AppGraph, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var posts by remember { mutableStateOf<List<MerchantPost>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showCompose by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<MerchantPost?>(null) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        error = null
        try {
            posts = apiCall { graph.merchantConsoleApi.posts() }.items
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
                    Text("店铺动态", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showCompose = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "发动态")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = posts
        when {
            list == null && error == null -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null && list == null -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            list!!.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center,
            ) { Text("还没有动态，点右上角 ➕ 发第一条", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { post ->
                    PostCard(post, onDelete = { deleteTarget = post })
                }
            }
        }
    }

    if (showCompose) {
        ComposePostSheet(
            graph = graph,
            onDismiss = { showCompose = false },
            onPublished = { showCompose = false; refreshKey++ },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除动态？") },
            text = { Text("删除后关注流中不再可见。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            deleting = true
                            try {
                                apiCall { graph.merchantConsoleApi.deletePost(target.id) }
                                deleteTarget = null
                                refreshKey++
                            } catch (e: Exception) {
                                snackbar.showSnackbar(e.userMessage)
                            } finally {
                                deleting = false
                            }
                        }
                    },
                    enabled = !deleting,
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("再想想") }
            },
        )
    }
}

@Composable
private fun PostCard(post: MerchantPost, onDelete: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    post.publishedAt.take(10),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            post.text?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (post.media.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(post.media.size) { idx ->
                        AsyncImage(
                            model = absoluteMediaUrl(post.media[idx].url),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
        }
    }
}

/** 发动态：文字或图片至少其一；图先逐张上传拿 key 再随发布引用；可挂本店商品（可购卡） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposePostSheet(
    graph: AppGraph,
    onDismiss: () -> Unit,
    onPublished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var pickedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 挂商品（可购卡）：消费者 feed 卡带价格、点击进商品详情
    var linkedProduct by remember { mutableStateOf<MerchantProduct?>(null) }
    var showProductPicker by remember { mutableStateOf(false) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris -> if (uris.isNotEmpty()) pickedUris = uris }

    fun publish() {
        if (text.isBlank() && pickedUris.isEmpty()) {
            error = "动态需要文字或图片"
            return
        }
        scope.launch {
            busy = true
            error = null
            try {
                val keys = pickedUris.map { uri ->
                    val (bytes, mime) = readImageBytes(context, uri)
                    apiCall {
                        graph.merchantConsoleApi.uploadPostImage(bytes.toRequestBody(mime.toMediaType()))
                    }.key
                }
                apiCall {
                    graph.merchantConsoleApi.createPost(
                        CreatePostRequest(
                            text = text.trim().ifEmpty { null },
                            mediaKeys = keys,
                            productCode = linkedProduct?.code,
                        )
                    )
                }
                onPublished()
            } catch (e: Exception) {
                error = e.userMessage
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("发动态", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("说点什么…（可选）") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (pickedUris.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pickedUris.size) { idx ->
                        AsyncImage(
                            model = pickedUris[idx],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
            // 挂商品行：选中显示商品名，点 × 取消挂载
            OutlinedButton(onClick = { showProductPicker = true }, enabled = !busy) {
                Text(linkedProduct?.let { "已挂商品：${it.name}（点击更换）" } ?: "挂商品（可选，顾客可点击购买）")
            }
            linkedProduct?.let {
                TextButton(onClick = { linkedProduct = null }) { Text("取消挂载") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        pickImages.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (pickedUris.isEmpty()) "选图（最多 9 张）" else "重新选图") }
                Button(
                    onClick = ::publish,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (busy) "发布中…" else "发布") }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.size(4.dp))
        }
    }

    if (showProductPicker) {
        ProductPickerSheet(
            graph = graph,
            onDismiss = { showProductPicker = false },
            onPicked = { linkedProduct = it; showProductPicker = false },
        )
    }
}

/** 本店商品单选（挂载可购卡用） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductPickerSheet(
    graph: AppGraph,
    onDismiss: () -> Unit,
    onPicked: (MerchantProduct) -> Unit,
) {
    var products by remember { mutableStateOf<List<MerchantProduct>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            products = apiCall { graph.merchantConsoleApi.products() }.items.filter { it.purchasable }
        } catch (e: Exception) {
            error = e.userMessage
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("选择要挂载的商品", style = MaterialTheme.typography.titleMedium)
            val list = products
            when {
                list == null && error == null -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                list!!.isEmpty() -> Text(
                    "没有在售商品",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                else -> LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(list, key = { it.code }) { p ->
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPicked(p) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
