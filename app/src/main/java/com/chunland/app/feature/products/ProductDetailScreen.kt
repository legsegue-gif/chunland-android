package com.chunland.app.feature.products

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.AiContext
import com.chunland.app.core.auth.LoginIntents
import com.chunland.app.core.network.absoluteMediaUrl
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.model.AddCartItemRequest
import com.chunland.app.data.model.ProductDetail
import com.chunland.app.feature.ai.ScopedAiSheet
import com.chunland.app.feature.common.ImageViewer
import com.chunland.app.feature.report.ReportSheet
import com.chunland.app.ui.formatPrice
import kotlinx.coroutines.launch

@Composable
fun ProductDetailScreen(
    graph: AppGraph,
    code: String,
    onBack: () -> Unit,
    requireLogin: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var detail by remember { mutableStateOf<ProductDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    val authState by graph.authManager.state.collectAsState()
    val followKeys by graph.followStore.keys.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(code) {
        try {
            detail = apiCall { graph.productApi.detail(code) }
        } catch (e: Exception) {
            error = e.userMessage
        }
    }
    LaunchedEffect(authState.isLoggedIn) {
        if (authState.isLoggedIn) graph.followStore.loadIfNeeded()
    }

    // 直接执行路径（不查登录态；intent retry 复用，见 LoginIntents 注释）
    val doAddToCart: (Int, String?) -> Unit = doAdd@{ quantity, selectedSize ->
        if (adding) return@doAdd
        scope.launch {
            adding = true
            try {
                apiCall { graph.cartApi.addItem(AddCartItemRequest(code, quantity, selectedSize)) }
                snackbar.showSnackbar("已加入购物车")
            } catch (e: Exception) {
                snackbar.showSnackbar(e.userMessage)
            } finally {
                adding = false
            }
        }
    }
    // 游客点加购 → 唤起登录层，登录成功自动续做该加购（对齐 iOS intent retry）
    val addToCart: (Int, String?) -> Unit = { quantity, selectedSize ->
        if (!authState.isLoggedIn) {
            LoginIntents.set { doAddToCart(quantity, selectedSize) }
            requireLogin()
        } else {
            doAddToCart(quantity, selectedSize)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // 自绘顶栏必须自己吃 status bar inset（edge-to-edge 下 Scaffold 不代劳）
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("商品详情", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    // 商品收藏（product 关注：只进「收藏与关注」管理页，不进 following 流）
                    val favorite = "product:$code" in followKeys
                    val doToggleFavorite: () -> Unit = {
                        scope.launch {
                            graph.followStore.loadIfNeeded()   // 幂等；retry 路径首次登录需先拉关注集
                            graph.followStore.toggle("product", code)
                                ?.let { snackbar.showSnackbar(it) }
                        }
                    }
                    IconButton(onClick = {
                        if (!authState.isLoggedIn) {
                            LoginIntents.set(doToggleFavorite)
                            requireLogin()
                        } else {
                            doToggleFavorite()
                        }
                    }) {
                        Icon(
                            if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (favorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // ✨ scoped AI（对齐 iOS AskAIButton .product 上下文）
                    if (detail != null) {
                        IconButton(onClick = { showAi = true }) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = "AI 助手")
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("举报商品") },
                            onClick = {
                                menuOpen = false
                                if (!authState.isLoggedIn) {
                                    LoginIntents.set { showReport = true }
                                    requireLogin()
                                } else {
                                    showReport = true
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        when {
            detail != null -> DetailContent(
                detail = detail!!,
                modifier = Modifier.padding(padding),
                adding = adding,
                onAddToCart = addToCart,
                onOpenImage = { viewerIndex = it },
            )
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            else -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    if (showReport) {
        ReportSheet(
            graph = graph,
            targetType = "product",
            targetKey = code,
            onDismiss = { showReport = false },
        )
    }
    if (showAi) {
        detail?.let { d ->
            ScopedAiSheet(
                graph = graph,
                context = AiContext.product(code, d.name),
                onDismiss = { showAi = false },
            )
        }
    }
    viewerIndex?.let { idx ->
        detail?.let { d ->
            ImageViewer(
                urls = d.zoomUrls.mapNotNull { absoluteMediaUrl(it) },
                initialIndex = idx,
                onDismiss = { viewerIndex = null },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    detail: ProductDetail,
    modifier: Modifier,
    adding: Boolean,
    onAddToCart: (Int, String?) -> Unit,
    onOpenImage: (Int) -> Unit,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val gallery = detail.galleryUrls
        if (gallery.isNotEmpty()) {
            val pagerState = rememberPagerState { gallery.size }
            Box {
                HorizontalPager(state = pagerState) { index ->
                    AsyncImage(
                        model = absoluteMediaUrl(gallery[index]),
                        contentDescription = detail.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clickable { onOpenImage(index) },
                    )
                }
                if (gallery.size > 1) {
                    Surface(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    ) {
                        Text(
                            "${pagerState.currentPage + 1}/${gallery.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(detail.name, style = MaterialTheme.typography.titleLarge)
            detail.englishName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 商品编号（商家货号），长按可复制（对齐 iOS textSelection）
            SelectionContainer {
                Text(
                    "编号 ${detail.code}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                detail.currentPrice?.let {
                    Text(
                        "¥${formatPrice(it)}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                val original = detail.originalPrice
                if (detail.discountAmount != null && original != null) {
                    Text(
                        "¥${formatPrice(original)}",
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = TextDecoration.LineThrough,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 生鲜商品按实重计价提示（对齐 iOS randomWeight 标签）
            if (detail.randomWeight) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = StockOrange,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "生鲜商品，按实重计价，以实际结算金额为准",
                        style = MaterialTheme.typography.bodySmall,
                        color = StockOrange,
                    )
                }
            }

            detail.merchantName?.let {
                HorizontalDivider()
                Text("店铺：$it", style = MaterialTheme.typography.bodyMedium)
            }

            // description 不渲染：爬虫源数据是原始 HTML 片段，iOS 端同样刻意跳过（对齐）；
            // 将来要展示需先做 HTML 解析/图片提取，不许直接 Text 输出。

            // 库存分级 + 单位（两者都缺时整行隐藏，对齐 iOS）
            if (detail.stockStatus != null || !detail.unitType.isNullOrEmpty()) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StockLabel(detail)
                    Spacer(Modifier.weight(1f))
                    detail.unitType?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            HorizontalDivider()

            // 尺码选择（有尺码必选才能加购，对齐 iOS；加购请求携带 selectedSize）
            var selectedSize by remember(detail.code) { mutableStateOf<String?>(null) }
            val sizes = detail.sizes.orEmpty()
            if (sizes.isNotEmpty()) {
                Text("尺码", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sizes.forEach { size ->
                        FilterChip(
                            selected = selectedSize == size,
                            onClick = { selectedSize = if (selectedSize == size) null else size },
                            label = { Text(size) },
                        )
                    }
                }
                HorizontalDivider()
            }

            val minQty = detail.minOrderQuantity.coerceAtLeast(1)
            var quantity by remember(detail.code) { mutableIntStateOf(minQty) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("数量", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { quantity-- }, enabled = quantity > minQty) {
                    Icon(Icons.Filled.Remove, contentDescription = "减少")
                }
                Text("$quantity", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { quantity++ }, enabled = quantity < detail.maxOrderQuantity) {
                    Icon(Icons.Filled.Add, contentDescription = "增加")
                }
            }

            val needsSize = sizes.isNotEmpty() && selectedSize == null
            val canBuy = detail.purchasable && !detail.outOfStock
            Button(
                onClick = { onAddToCart(quantity, selectedSize) },
                enabled = canBuy && !adding && !needsSize,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        !canBuy -> "暂不可购买"
                        needsSize -> "请选择尺码"
                        adding -> "加入中…"
                        else -> "加入购物车"
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// 库存分级配色（Material 语义色外的状态色，与 iOS green/orange/red 对齐）
private val StockGreen = Color(0xFF2E7D32)
private val StockOrange = Color(0xFFF57C00)

/** 库存分级标签：有货 / 仅剩 N 件 / 已售罄；unknown 不显示（对齐 iOS stockTier）。 */
@Composable
private fun StockLabel(detail: ProductDetail) {
    val (text, color, icon) = when (detail.stockStatus) {
        "inStock" -> Triple("有货", StockGreen, Icons.Filled.CheckCircle)
        "lowStock" -> Triple(
            detail.stockLevel?.let { "仅剩 $it 件" } ?: "库存紧张",
            StockOrange,
            Icons.Filled.Warning,
        )
        "outOfStock" -> Triple("已售罄", MaterialTheme.colorScheme.error, Icons.Filled.Warning)
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
