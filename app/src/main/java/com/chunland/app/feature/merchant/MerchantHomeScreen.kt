package com.chunland.app.feature.merchant

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.absoluteMediaUrl
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.CreateProductRequest
import com.chunland.app.data.model.MerchantProduct
import com.chunland.app.data.model.MerchantStats
import com.chunland.app.data.model.MyStore
import com.chunland.app.data.model.Region
import com.chunland.app.data.model.SetProductImagesRequest
import com.chunland.app.data.model.UpdateProductRequest
import com.chunland.app.data.model.UpdateStoreRequest
import com.chunland.app.ui.formatPrice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** photo picker Uri → (bytes, mime)，与凭证上传同口径 */
internal suspend fun readImageBytes(context: Context, uri: Uri): Pair<ByteArray, String> =
    withContext(Dispatchers.IO) {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取图片" }
            .use { it.readBytes() }
        bytes to (context.contentResolver.getType(uri) ?: "image/jpeg")
    }

/**
 * 商家「店铺」tab（首片）：店铺信息 + 经营数据 + 自家商品管理（含已下架）。
 * 零资金流 —— 本页只管商品，不涉收款。logo/多图/尺码/动态/分类方案后续增量。
 */
@Composable
fun MerchantHomeScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    snackbar: androidx.compose.material3.SnackbarHostState,
    onOpenPosts: () -> Unit,
    onOpenSchemes: () -> Unit,
) {
    var store by remember { mutableStateOf<MyStore?>(null) }
    var products by remember { mutableStateOf<List<MerchantProduct>>(emptyList()) }
    var stats by remember { mutableStateOf<MerchantStats?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<MerchantProduct?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // 店铺 logo：选中即上传（raw 二进制，与凭证同口径），上传即生效
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logoUploading by remember { mutableStateOf(false) }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            logoUploading = true
            try {
                val (bytes, mime) = readImageBytes(context, uri)
                apiCall { graph.merchantConsoleApi.uploadLogo(bytes.toRequestBody(mime.toMediaType())) }
                refreshKey++   // 先刷新再 toast（showSnackbar 挂起到消失，别拖住 reload）
                launch { snackbar.showSnackbar("logo 已更新") }
            } catch (e: Exception) {
                snackbar.showSnackbar(e.userMessage)
            } finally {
                logoUploading = false
            }
        }
    }

    LaunchedEffect(refreshKey) {
        error = null
        try {
            coroutineScope {
                val s = async { apiCall { graph.merchantConsoleApi.myStore() } }
                val p = async { apiCall { graph.merchantConsoleApi.products() } }
                val st = async { apiCall { graph.merchantConsoleApi.stats() } }
                store = s.await()
                products = p.await().items
                stats = st.await()
            }
        } catch (e: Exception) {
            error = e.userMessage
            // 已有内容时的刷新失败（如建品后的重载）不能静默：列表会与服务端短暂不一致
            if (store != null) snackbar.showSnackbar("刷新失败：${e.userMessage}")
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        ) {
            Text(
                store?.name ?: "我的店铺",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showSettings = true }, enabled = store != null) {
                Icon(Icons.Filled.Settings, contentDescription = "店铺设置")
            }
            IconButton(onClick = { showCreate = true }, enabled = store != null) {
                Icon(Icons.Filled.Add, contentDescription = "新增商品")
            }
        }

        val bottom = contentPadding.calculateBottomPadding()
        when {
            !loaded -> Box(
                Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null && store == null -> Box(
                Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center,
            ) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                store?.let { s ->
                    item(key = "store") {
                        StoreCard(
                            store = s,
                            products = products,
                            logoUploading = logoUploading,
                            onChangeLogo = {
                                pickLogo.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onOpenPosts = onOpenPosts,
                            onOpenSchemes = onOpenSchemes,
                        )
                    }
                }
                stats?.let { st ->
                    item(key = "stats") { StatsCard(st) }
                }
                item(key = "products-header") {
                    Text("商品", style = MaterialTheme.typography.titleMedium)
                }
                if (products.isEmpty()) {
                    item(key = "products-empty") {
                        Text(
                            "还没有商品，点右上角 ➕ 上架第一件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(products, key = { it.code }) { p ->
                        ProductRow(p) { editTarget = p }
                    }
                }
            }
        }
    }

    if (showCreate || editTarget != null) {
        ProductFormSheet(
            graph = graph,
            product = editTarget,
            onDismiss = { showCreate = false; editTarget = null },
            onSaved = {
                showCreate = false; editTarget = null; refreshKey++
            },
        )
    }
    val s = store
    if (showSettings && s != null) {
        StoreSettingsSheet(
            graph = graph,
            store = s,
            onDismiss = { showSettings = false },
            onSaved = { showSettings = false; refreshKey++ },
        )
    }
}

@Composable
private fun StoreCard(
    store: MyStore,
    products: List<MerchantProduct>,
    logoUploading: Boolean,
    onChangeLogo: () -> Unit,
    onOpenPosts: () -> Unit,
    onOpenSchemes: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (store.logoUrl != null) {
                    AsyncImage(
                        model = absoluteMediaUrl(store.logoUrl),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(CircleShape),
                    )
                } else {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = onChangeLogo, enabled = !logoUploading) {
                    Text(if (logoUploading) "上传中…" else "更换 logo")
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onOpenPosts) {
                    Icon(Icons.Filled.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("店铺动态")
                }
            }
            Row {
                OutlinedButton(onClick = onOpenSchemes, modifier = Modifier.fillMaxWidth()) {
                    Text("分类方案（顾客进店的浏览视角）")
                }
            }
            HorizontalDivider()
            Row {
                Text("在售商品", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "${products.count { it.purchasable }} / ${products.size}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row {
                Text("起送金额", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    store.minOrderAmount?.let { "¥${formatPrice(it)}" } ?: "平台默认",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (store.areaCode == null) {
                Text(
                    "尚未设置发货地，下单时距离代购费按 0 计",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun StatsCard(stats: MerchantStats) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("经营数据", style = MaterialTheme.typography.titleSmall)
            Row {
                StatCell("总订单", "${stats.totalOrders}", Modifier.weight(1f))
                StatCell("总货值", "¥${formatPrice(stats.gmv)}", Modifier.weight(1f))
            }
            Row {
                StatCell("近30天订单", "${stats.last30dOrders}", Modifier.weight(1f))
                StatCell("近30天货值", "¥${formatPrice(stats.last30dGmv)}", Modifier.weight(1f))
            }
            if (stats.topProducts.isNotEmpty()) {
                HorizontalDivider()
                Text("热销", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                stats.topProducts.take(3).forEach { t ->
                    Row {
                        Text(t.name, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f), maxLines = 1)
                        Text("×${t.qty}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ProductRow(p: MerchantProduct, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(10.dp),
        ) {
            AsyncImage(
                model = absoluteMediaUrl(p.thumbnail),
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    p.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    color = if (p.purchasable) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                p.price?.let {
                    Text(
                        "¥${formatPrice(it)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (p.purchasable) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!p.purchasable) {
                Text(
                    "已下架",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 建品（product=null）/ 编辑：名称、价格、描述；编辑时可上/下架。
 * 商品图两步式（对齐 iOS ProductFormView）：保存后逐张上传拿 key → PUT 整体挂载
 * （keys[0] 主图）；编辑时**新选图整体替换原有图**、不选则不动 —— 刻意不做 URL↔key 混编。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductFormSheet(
    graph: AppGraph,
    product: MerchantProduct?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf(product?.name ?: "") }
    var price by remember { mutableStateOf(product?.price?.let { formatPrice(it) } ?: "") }
    var description by remember { mutableStateOf(product?.description ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 新建路径已成功建品的 code：传图失败后重试只补 update/挂图，不再 create（防重复建品）
    var createdCode by remember { mutableStateOf<String?>(null) }

    var pickedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris -> if (uris.isNotEmpty()) pickedUris = uris }

    fun save(purchasable: Boolean? = null) {
        val n = name.trim()
        val v = price.trim().toDoubleOrNull()
        if (purchasable == null && (n.isEmpty() || v == null || v <= 0)) {
            error = "请填写商品名和大于 0 的价格"
            return
        }
        scope.launch {
            busy = true
            error = null
            try {
                if (purchasable != null) {
                    // 上/下架单独提交，不携带表单其他字段（避免未改完的输入被顺带保存）
                    apiCall {
                        graph.merchantConsoleApi.updateProduct(
                            product!!.code, UpdateProductRequest(purchasable = purchasable)
                        )
                    }
                } else {
                    // 新建成功但传图失败后重试：走 update 分支补齐，绝不二次 create（防重复建品）
                    val existingCode = product?.code ?: createdCode
                    val saved = if (existingCode == null) {
                        apiCall {
                            graph.merchantConsoleApi.createProduct(
                                CreateProductRequest(n, v!!, description.trim().ifEmpty { null })
                            )
                        }.also { createdCode = it.code }
                    } else {
                        apiCall {
                            graph.merchantConsoleApi.updateProduct(
                                existingCode,
                                UpdateProductRequest(
                                    name = n,
                                    price = v,
                                    description = description.trim().ifEmpty { null },
                                )
                            )
                        }
                    }
                    if (pickedUris.isNotEmpty()) {
                        val keys = pickedUris.map { uri ->
                            val (bytes, mime) = readImageBytes(context, uri)
                            apiCall {
                                graph.merchantConsoleApi.uploadProductAsset(bytes.toRequestBody(mime.toMediaType()))
                            }.key
                        }
                        apiCall {
                            graph.merchantConsoleApi.setProductImages(saved.code, SetProductImagesRequest(keys))
                        }
                    }
                }
                onSaved()
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
            Text(
                if (product == null) "新增商品" else "编辑商品",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("商品名") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = price, onValueChange = { price = it },
                label = { Text("价格（元）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("描述（可选）") },
                modifier = Modifier.fillMaxWidth(),
            )

            // 商品图：新选预览优先，否则显示现有相册；第 1 张为主图
            val existing = product?.gallery.orEmpty()
            if (pickedUris.isNotEmpty()) {
                ImageStrip(models = pickedUris)
                Text(
                    "保存后将以新选的 ${pickedUris.size} 张图整体替换原有图片，第 1 张为主图",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (existing.isNotEmpty()) {
                ImageStrip(models = existing.map { absoluteMediaUrl(it) })
            }
            OutlinedButton(
                onClick = {
                    pickImages.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !busy,
            ) { Text(if (pickedUris.isEmpty()) "选择图片（最多 9 张）" else "重新选择") }

            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { save() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "保存中…" else "保存") }
            if (product != null) {
                OutlinedButton(
                    onClick = { save(purchasable = !product.purchasable) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (product.purchasable) "下架" else "重新上架") }
            }
        }
    }
}

/** 横向缩略图条（model 接受 Uri / URL 字符串，Coil 都吃） */
@Composable
private fun ImageStrip(models: List<Any?>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(models.size) { idx ->
            AsyncImage(
                model = models[idx],
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

/** 店铺设置：名称 / 发货地（改后报价即时生效）/ 起送金额。发货地级联叠一层 sheet。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreSettingsSheet(
    graph: AppGraph,
    store: MyStore,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(store.name) }
    var minOrder by remember { mutableStateOf(store.minOrderAmount?.let { formatPrice(it) } ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var province by remember { mutableStateOf<Region?>(null) }
    var city by remember { mutableStateOf<Region?>(null) }
    var area by remember { mutableStateOf<Region?>(null) }
    var pickerLevel by remember { mutableStateOf(0) }
    var options by remember { mutableStateOf<List<Region>>(emptyList()) }

    // 现有发货地回显名称：regions 表 code 是层级前缀制（省 2 位、市 4 位、区县 6 位），
    // 由区县 code 取前缀逐级反查名称；失败回退显示 code
    var currentAreaName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store.areaCode) {
        val code = store.areaCode ?: return@LaunchedEffect
        currentAreaName = runCatching {
            val provinceCode = code.take(2)
            val cityCode = code.take(4)
            val p = apiCall { graph.regionApi.children(null) }.firstOrNull { it.code == provinceCode }
            val c = apiCall { graph.regionApi.children(provinceCode) }.firstOrNull { it.code == cityCode }
            val a = apiCall { graph.regionApi.children(cityCode) }.firstOrNull { it.code == code }
            listOfNotNull(p?.name, c?.name, a?.name).joinToString(" ").ifBlank { null }
        }.getOrNull()
    }

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
                error = e.userMessage
            }
        }
    }

    fun save() {
        val n = name.trim()
        if (n.isEmpty()) { error = "店铺名不能为空"; return }
        val minText = minOrder.trim()
        val minValue = if (minText.isEmpty()) null else minText.toDoubleOrNull()
        if (minText.isNotEmpty() && minValue == null) { error = "起送金额需为数字"; return }
        scope.launch {
            busy = true
            error = null
            try {
                apiCall {
                    graph.merchantConsoleApi.updateStore(
                        UpdateStoreRequest(
                            name = if (n != store.name) n else null,
                            areaCode = area?.code,           // 未选 = 不改（null 不发）
                            minOrderAmount = minValue,        // 留空 = 不改；不支持清空回退
                        )
                    )
                }
                onSaved()
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
            Text("店铺设置", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("店铺名") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = minOrder, onValueChange = { minOrder = it },
                label = { Text("起送金额（元，留空不改）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "发货地：${
                    if (area != null) listOfNotNull(province?.name, city?.name, area?.name).joinToString(" ")
                    else currentAreaName ?: store.areaCode ?: "未设置"
                }",
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
                onClick = ::save,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "保存中…" else "保存") }
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
