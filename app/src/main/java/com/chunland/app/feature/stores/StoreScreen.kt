package com.chunland.app.feature.stores

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chunland.app.core.AppGraph
import com.chunland.app.core.auth.LoginIntents
import com.chunland.app.core.network.apiCall
import com.chunland.app.data.model.Category
import com.chunland.app.data.model.CategoryScheme
import com.chunland.app.data.model.FeedItem
import com.chunland.app.data.model.SchemeCategory
import com.chunland.app.feature.ai.StoreAiSheet
import com.chunland.app.feature.products.ProductListScreen
import kotlinx.coroutines.launch

/**
 * 进店页（对齐 iOS StoreView）：商品/动态双 face。
 * 商品 face：搜索 + 分类方案 lens ——有可见方案时显示「官方 + 各方案」切换行，
 * 方案视角 = 左侧方案分类栏 + 商品区（恒定侧栏，与官方层级布局同型；
 * products?schemeCategory=，与官方 category 互斥）；
 * 动态 face：该店公开动态流（StorePostsList）。
 */
@Composable
fun StoreScreen(
    graph: AppGraph,
    merchantId: Int,
    merchantName: String,
    onBack: () -> Unit,
    onOpenProduct: (String) -> Unit,
    onOpenFeedItem: (FeedItem) -> Unit,
    requireLogin: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val authState by graph.authManager.state.collectAsState()
    val followKeys by graph.followStore.keys.collectAsState()

    LaunchedEffect(authState.isLoggedIn) {
        if (authState.isLoggedIn) graph.followStore.loadIfNeeded()
    }

    var face by rememberSaveable { mutableStateOf("products") }
    var schemes by remember { mutableStateOf<List<CategoryScheme>>(emptyList()) }
    var activeScheme by remember { mutableStateOf<CategoryScheme?>(null) }
    var schemeCat by remember { mutableStateOf<SchemeCategory?>(null) }   // 侧栏选中的一级
    var schemeL2 by remember { mutableStateOf<SchemeCategory?>(null) }    // 一级下选中的二级（chips）
    var showAi by remember { mutableStateOf(false) }

    // 官方分类树（对齐 iOS StoreView：布局按树形状自适应 —— 有子级 → 左侧栏 L1 +
    // 顶部 L2 chips；全平铺（品牌店等分类无子级时）→ 顶部 chips；空 → 隐藏导航）
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selectedL1 by remember { mutableStateOf<Category?>(null) }
    var selectedL2 by remember { mutableStateOf<Category?>(null) }
    val isHierarchical = categories.any { it.children.isNotEmpty() }

    LaunchedEffect(merchantId) {
        // 方案/分类加载失败都不打断进店（官方视角/无导航兜底）
        runCatching {
            val loaded = apiCall { graph.merchantApi.schemes(merchantId) }.items
            schemes = loaded
            activeScheme = loaded.firstOrNull { it.isDefault }
        }
        runCatching { categories = apiCall { graph.categoryApi.tree(merchantId) } }
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
                    Text(
                        merchantName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 关注商家（对齐 iOS StoreView FollowButton：更新进「正在关注」流）
                    val followed = "merchant:$merchantId" in followKeys
                    val doToggleFollow: () -> Unit = {
                        scope.launch {
                            graph.followStore.loadIfNeeded()
                            graph.followStore.toggle("merchant", merchantId.toString())
                                ?.let { snackbar.showSnackbar(it) }
                        }
                    }
                    TextButton(onClick = {
                        if (!authState.isLoggedIn) {
                            LoginIntents.set(doToggleFollow)   // 登录成功自动续做
                            requireLogin()
                        } else {
                            doToggleFollow()
                        }
                    }) {
                        Text(if (followed) "已关注" else "+ 关注")
                    }
                    // 进店 ✨：scoped AI 会话（搜索/分类经 AiToolScope 硬限定本店）
                    IconButton(onClick = { showAi = true }) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "AI 选购助手")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            // 商品 / 动态双 face
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                FilterChip(selected = face == "products", onClick = { face = "products" }, label = { Text("商品") })
                FilterChip(selected = face == "posts", onClick = { face = "posts" }, label = { Text("动态") })
            }

            if (face == "posts") {
                StorePostsList(
                    graph = graph,
                    merchantId = merchantId,
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                    onOpenProduct = onOpenProduct,
                    onOpenFeedItem = onOpenFeedItem,
                )
                return@Column
            }

            if (schemes.isNotEmpty()) {
                // 视角切换：官方 + 各方案（切换即清分类选择，对齐 iOS switchViewMode）
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "official") {
                        FilterChip(
                            selected = activeScheme == null,
                            onClick = {
                                activeScheme = null; schemeCat = null; schemeL2 = null
                                selectedL1 = null; selectedL2 = null
                            },
                            label = { Text("官方") },
                        )
                    }
                    items(schemes, key = { it.id }) { scheme ->
                        FilterChip(
                            selected = activeScheme?.id == scheme.id,
                            onClick = {
                                activeScheme = scheme; schemeCat = null; schemeL2 = null
                                selectedL1 = null; selectedL2 = null
                            },
                            label = { Text(scheme.name) },
                        )
                    }
                }
            }

            val scheme = activeScheme
            if (scheme != null) {
                // 方案视角：左侧方案分类栏 + 商品区（恒定侧栏，对齐 iOS schemeBody）。
                // 一级有二级时顶部出 L2 chips（选一级 = 本级 + 全部二级，服务端展开）
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    SchemeSidebar(
                        categories = scheme.categories,
                        selectedId = schemeCat?.id,
                        onSelect = { schemeCat = it; schemeL2 = null },
                    )
                    VerticalDivider()
                    Column(Modifier.weight(1f)) {
                        val subs = schemeCat?.children.orEmpty()
                        if (subs.isNotEmpty()) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item(key = "scheme-l2-all") {
                                    FilterChip(
                                        selected = schemeL2 == null,
                                        onClick = { schemeL2 = null },
                                        label = { Text("全部") },
                                    )
                                }
                                items(subs, key = { it.id }) { sub ->
                                    FilterChip(
                                        selected = schemeL2?.id == sub.id,
                                        onClick = { schemeL2 = sub },
                                        label = { Text(sub.name) },
                                    )
                                }
                            }
                        }
                        ProductListScreen(
                            graph = graph,
                            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                            snackbar = snackbar,
                            merchantId = merchantId,
                            schemeCategoryId = (schemeL2 ?: schemeCat)?.id,
                            onOpenProduct = onOpenProduct,
                        )
                    }
                }
            } else if (isHierarchical) {
                // 层级分类：左侧 L1 栏 + 右列（L2 chips + 商品区），对齐 iOS hierarchicalBody
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    CategorySidebar(
                        categories = categories,
                        selectedCode = selectedL1?.code,
                        onSelect = { selectedL1 = it; selectedL2 = null },
                    )
                    VerticalDivider()
                    Column(Modifier.weight(1f)) {
                        val children = selectedL1?.children.orEmpty()
                        if (children.isNotEmpty()) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item(key = "l2-all") {
                                    FilterChip(
                                        selected = selectedL2 == null,
                                        onClick = { selectedL2 = null },
                                        label = { Text("全部") },
                                    )
                                }
                                items(children, key = { it.code }) { l2 ->
                                    FilterChip(
                                        selected = selectedL2?.code == l2.code,
                                        onClick = { selectedL2 = l2 },
                                        label = { Text(l2.name) },
                                    )
                                }
                            }
                        }
                        ProductListScreen(
                            graph = graph,
                            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                            snackbar = snackbar,
                            merchantId = merchantId,
                            categoryCode = selectedL2?.code ?: selectedL1?.code,
                            onOpenProduct = onOpenProduct,
                        )
                    }
                }
            } else {
                if (categories.isNotEmpty()) {
                    // 平铺分类（品牌店等）：顶部 chips，对齐 iOS brandBody
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "cat-all") {
                            FilterChip(
                                selected = selectedL1 == null,
                                onClick = { selectedL1 = null; selectedL2 = null },
                                label = { Text("全部") },
                            )
                        }
                        items(categories, key = { it.code }) { cat ->
                            FilterChip(
                                selected = selectedL1?.code == cat.code,
                                onClick = { selectedL1 = cat; selectedL2 = null },
                                label = { Text(cat.name) },
                            )
                        }
                    }
                }
                ProductListScreen(
                    graph = graph,
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                    snackbar = snackbar,
                    merchantId = merchantId,
                    categoryCode = selectedL1?.code,
                    onOpenProduct = onOpenProduct,
                )
            }
        }
    }

    if (showAi) {
        StoreAiSheet(
            graph = graph,
            merchantId = merchantId,
            merchantName = merchantName,
            onDismiss = { showAi = false },
        )
    }
}

/** 方案视角左侧栏（恒定侧栏：「全部」+ 一级方案分类，对齐 iOS schemeBody） */
@Composable
private fun SchemeSidebar(
    categories: List<SchemeCategory>,
    selectedId: Int?,
    onSelect: (SchemeCategory?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .width(84.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        item(key = "scheme-all") {
            SidebarItem("全部", selected = selectedId == null) { onSelect(null) }
        }
        items(categories, key = { it.id }) { cat ->
            SidebarItem(cat.name, selected = selectedId == cat.id) { onSelect(cat) }
        }
    }
}

/** 层级分类左侧栏（对齐 iOS StoreView sidebar：84dp 窄栏，「全部」+ L1 列表） */
@Composable
private fun CategorySidebar(
    categories: List<Category>,
    selectedCode: String?,
    onSelect: (Category?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .width(84.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        item(key = "sidebar-all") {
            SidebarItem("全部", selected = selectedCode == null) { onSelect(null) }
        }
        items(categories, key = { it.code }) { cat ->
            SidebarItem(cat.name, selected = selectedCode == cat.code) { onSelect(cat) }
        }
    }
}

@Composable
private fun SidebarItem(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 6.dp),
        )
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
