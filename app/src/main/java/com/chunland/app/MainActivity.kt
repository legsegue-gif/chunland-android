package com.chunland.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chunland.app.core.AppGraph
import com.chunland.app.core.auth.LoginIntents
import com.chunland.app.data.model.FeedItem
import com.chunland.app.data.model.Merchant
import com.chunland.app.feature.feed.FeedDetailScreen
import com.chunland.app.feature.feed.FeedItemStash
import com.chunland.app.feature.addresses.AddressesScreen
import com.chunland.app.feature.agent.AgentSettingsScreen
import com.chunland.app.feature.agent.AgentWorkbenchScreen
import com.chunland.app.feature.agent.HallScreen
import com.chunland.app.feature.agent.PurchaseListScreen
import com.chunland.app.feature.agent.SettlementsScreen
import com.chunland.app.feature.ai.AiChatScreen
import com.chunland.app.feature.auth.LoginScreen
import com.chunland.app.feature.cart.CartScreen
import com.chunland.app.feature.checkout.AddressFormScreen
import com.chunland.app.feature.checkout.CheckoutScreen
import com.chunland.app.feature.feed.FeedScreen
import com.chunland.app.feature.follows.FollowsManageScreen
import com.chunland.app.feature.merchant.MerchantHomeScreen
import com.chunland.app.feature.merchant.MerchantOrdersScreen
import com.chunland.app.feature.merchant.MerchantPostsScreen
import com.chunland.app.feature.merchant.OpenStoreScreen
import com.chunland.app.feature.merchant.SchemeManageScreen
import com.chunland.app.feature.orders.OrderDetailScreen
import com.chunland.app.feature.orders.OrdersScreen
import com.chunland.app.feature.products.ProductDetailScreen
import com.chunland.app.feature.profile.AccountScreen
import com.chunland.app.feature.profile.BlockedUsersScreen
import com.chunland.app.feature.profile.ProfileScreen
import com.chunland.app.feature.stores.StoreListScreen
import com.chunland.app.feature.stores.StoreScreen
import com.chunland.app.ui.theme.ChunlandTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChunlandTheme {
                AppRoot(appGraph)
            }
        }
    }
}

/**
 * 游客模式（对齐 iOS）：启动直接进 tab 页自由浏览，账号类动作经 requireLogin 唤起登录层；
 * 登录成功由 AuthManager.state 翻转自动收起。401 刷新失败的强制登出同样只是回到游客态。
 */
@Composable
private fun AppRoot(graph: AppGraph) {
    val authState by graph.authManager.state.collectAsState()
    var showLogin by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(authState.isLoggedIn) {
        if (authState.isLoggedIn) {
            showLogin = false
            // intent retry：登录成功自动续做被拦截的动作（对齐 iOS LoginCoordinator）
            LoginIntents.consume()?.invoke()
        }
    }

    Box {
        MainNavHost(graph, requireLogin = { showLogin = true })
        if (showLogin) {
            Surface(Modifier.fillMaxSize()) {
                LoginScreen(graph, onDismiss = {
                    showLogin = false
                    LoginIntents.clear()   // 手动关闭 = 放弃续做
                })
            }
        }
    }
}

@Composable
private fun MainNavHost(graph: AppGraph, requireLogin: () -> Unit) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "tabs") {
        composable("tabs") {
            TabsScreen(
                graph = graph,
                requireLogin = requireLogin,
                onOpenStore = { m -> nav.navigate("store/${m.id}?name=${Uri.encode(m.name)}") },
                onCheckout = { nav.navigate("checkout") },
                onOpenOrders = { nav.navigate("orders") },
                onOpenAddresses = { nav.navigate("addresses") },
                onOpenFollows = { nav.navigate("follows") },
                onOpenProduct = { code -> nav.navigate("product/$code") },
                onOpenOrder = { id -> nav.navigate("order/$id") },
                onOpenStoreForm = { nav.navigate("merchant/open") },
                onOpenSettlements = { nav.navigate("settlements") },
                onOpenMerchantPosts = { nav.navigate("merchant/posts") },
                onOpenMerchantSchemes = { nav.navigate("merchant/schemes") },
                onOpenFeedItem = { item ->
                    FeedItemStash.put(item)
                    nav.navigate("feed/${item.id}")
                },
                onOpenAccount = { nav.navigate("account") },
                onOpenBlocks = { nav.navigate("blocks") },
                onOpenPurchaseList = { nav.navigate("agent/purchase-list") },
                onOpenAgentSettings = { nav.navigate("agent/settings") },
            )
        }
        composable("account") {
            AccountScreen(graph = graph, onBack = { nav.popBackStack() })
        }
        composable("blocks") {
            BlockedUsersScreen(graph = graph, onBack = { nav.popBackStack() })
        }
        composable("agent/purchase-list") {
            PurchaseListScreen(
                graph = graph,
                onBack = { nav.popBackStack() },
                onOpenOrder = { id -> nav.navigate("order/$id") },
            )
        }
        composable("agent/settings") {
            AgentSettingsScreen(graph = graph, onBack = { nav.popBackStack() })
        }
        composable(
            route = "feed/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            FeedDetailScreen(
                graph = graph,
                itemId = id,
                onBack = { nav.popBackStack() },
                requireLogin = requireLogin,
            )
        }
        composable("settlements") {
            SettlementsScreen(graph = graph, onBack = { nav.popBackStack() })
        }
        composable("merchant/posts") {
            MerchantPostsScreen(graph = graph, onBack = { nav.popBackStack() })
        }
        composable("merchant/schemes") {
            SchemeManageScreen(graph = graph, onBack = { nav.popBackStack() })
        }
        composable("merchant/open") {
            OpenStoreScreen(
                graph = graph,
                onBack = { nav.popBackStack() },
                // 开店成功 activeIdentity 已切 merchant，回 tabs 自动换商家布局
                onOpened = { nav.popBackStack() },
            )
        }
        composable("follows") {
            FollowsManageScreen(
                graph = graph,
                onBack = { nav.popBackStack() },
                onOpenProduct = { code -> nav.navigate("product/$code") },
            )
        }
        composable("addresses") {
            AddressesScreen(
                graph = graph,
                onBack = { nav.popBackStack() },
                onNewAddress = { nav.navigate("address/new") },
            )
        }
        composable("checkout") {
            CheckoutScreen(
                graph = graph,
                onBack = { nav.popBackStack() },
                onNewAddress = { nav.navigate("address/new") },
                // 下单成功 → 弹掉结算页，落到订单列表
                onPlaced = { nav.navigate("orders") { popUpTo("tabs") } },
            )
        }
        composable("address/new") {
            AddressFormScreen(
                graph = graph,
                onBack = { nav.popBackStack() },
                onSaved = { nav.popBackStack() },
            )
        }
        composable("orders") {
            OrdersScreen(
                graph = graph,
                onBack = { nav.popBackStack() },
                onOpenOrder = { id -> nav.navigate("order/$id") },
            )
        }
        composable(
            route = "order/{id}",
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            val id = entry.arguments?.getInt("id") ?: return@composable
            OrderDetailScreen(
                graph, id, onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = "store/{id}?name={name}",
            arguments = listOf(
                navArgument("id") { type = NavType.IntType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val id = entry.arguments?.getInt("id") ?: return@composable
            val name = entry.arguments?.getString("name").orEmpty()
            StoreScreen(
                graph = graph,
                merchantId = id,
                merchantName = name,
                onBack = { nav.popBackStack() },
                onOpenProduct = { code -> nav.navigate("product/$code") },
                onOpenFeedItem = { item ->
                    FeedItemStash.put(item)
                    nav.navigate("feed/${item.id}")
                },
                requireLogin = requireLogin,
            )
        }
        composable("product/{code}") { entry ->
            val code = entry.arguments?.getString("code") ?: return@composable
            ProductDetailScreen(
                graph = graph,
                code = code,
                onBack = { nav.popBackStack() },
                requireLogin = requireLogin,
            )
        }
    }
}

/**
 * 按活跃身份选布局（对齐 iOS MainTabView）：
 * consumer 5 tab（发现/店铺/AI/购物车/我的）；agent 4 tab（工作台/接单大厅/AI/我的）；
 * merchant 4 tab（店铺/订单/AI/我的）。身份切换时 tab 落回该布局首页（rememberSaveable 以布局为 key）。
 */
@Composable
private fun TabsScreen(
    graph: AppGraph,
    requireLogin: () -> Unit,
    onOpenStore: (Merchant) -> Unit,
    onCheckout: () -> Unit,
    onOpenOrders: () -> Unit,
    onOpenAddresses: () -> Unit,
    onOpenFollows: () -> Unit,
    onOpenProduct: (String) -> Unit,
    onOpenOrder: (Int) -> Unit,
    onOpenStoreForm: () -> Unit,
    onOpenSettlements: () -> Unit,
    onOpenMerchantPosts: () -> Unit,
    onOpenMerchantSchemes: () -> Unit,
    onOpenFeedItem: (FeedItem) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenBlocks: () -> Unit,
    onOpenPurchaseList: () -> Unit,
    onOpenAgentSettings: () -> Unit,
) {
    val authState by graph.authManager.state.collectAsState()
    val layout = if (authState.isLoggedIn) authState.activeIdentity else "consumer"
    val agentLayout = layout == "agent"
    val merchantLayout = layout == "merchant"
    var tab by rememberSaveable(layout) { mutableIntStateOf(0) }
    // 「发现」tab 重选信号：已选中时再点 → FeedScreen 回顶/刷新（对齐 iOS TabRouter）
    var feedReselect by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    val profilePane: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit = { padding ->
        ProfileScreen(
            graph, padding, snackbar,
            onRequireLogin = requireLogin,
            onOpenOrders = onOpenOrders,
            onOpenAddresses = onOpenAddresses,
            onOpenFollows = onOpenFollows,
            onOpenStoreForm = onOpenStoreForm,
            onOpenSettlements = onOpenSettlements,
            onOpenAccount = onOpenAccount,
            onOpenBlocks = onOpenBlocks,
            onOpenAgentSettings = onOpenAgentSettings,
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                when {
                    merchantLayout -> {
                        TabItem(tab, 0, Icons.Filled.Storefront, "店铺") { tab = 0 }
                        TabItem(tab, 1, Icons.Filled.ReceiptLong, "订单") { tab = 1 }
                        TabItem(tab, 2, Icons.Filled.AutoAwesome, "AI助手") { tab = 2 }
                        TabItem(tab, 3, Icons.Filled.Person, "我的") { tab = 3 }
                    }
                    agentLayout -> {
                        TabItem(tab, 0, Icons.Filled.Dashboard, "工作台") { tab = 0 }
                        TabItem(tab, 1, Icons.Filled.Inbox, "接单大厅") { tab = 1 }
                        TabItem(tab, 2, Icons.Filled.AutoAwesome, "AI助手") { tab = 2 }
                        TabItem(tab, 3, Icons.Filled.Person, "我的") { tab = 3 }
                    }
                    else -> {
                        TabItem(tab, 0, Icons.Filled.Newspaper, "发现") {
                            if (tab == 0) feedReselect++ else tab = 0
                        }
                        TabItem(tab, 1, Icons.Filled.Storefront, "店铺") { tab = 1 }
                        TabItem(tab, 2, Icons.Filled.AutoAwesome, "AI助手") { tab = 2 }
                        TabItem(tab, 3, Icons.Filled.ShoppingCart, "购物车") { tab = 3 }
                        TabItem(tab, 4, Icons.Filled.Person, "我的") { tab = 4 }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            merchantLayout -> when (tab) {
                0 -> MerchantHomeScreen(
                    graph, padding, snackbar,
                    onOpenPosts = onOpenMerchantPosts,
                    onOpenSchemes = onOpenMerchantSchemes,
                )
                1 -> MerchantOrdersScreen(graph, padding, snackbar)
                2 -> AiChatScreen(graph, padding, snackbar)
                else -> profilePane(padding)
            }
            agentLayout -> when (tab) {
                0 -> AgentWorkbenchScreen(
                    graph, padding, snackbar,
                    onOpenOrder = onOpenOrder,
                    onOpenPurchaseList = onOpenPurchaseList,
                )
                1 -> HallScreen(graph, padding, snackbar, onOpenOrder = onOpenOrder)
                2 -> AiChatScreen(graph, padding, snackbar)
                else -> profilePane(padding)
            }
            else -> when (tab) {
                0 -> FeedScreen(
                    graph, padding, snackbar,
                    requireLogin = requireLogin,
                    onOpenProduct = onOpenProduct,
                    onOpenFeedItem = onOpenFeedItem,
                    reselectSignal = feedReselect,
                )
                1 -> StoreListScreen(graph, padding, onOpenStore)
                2 -> AiChatScreen(graph, padding, snackbar)
                3 -> if (authState.isLoggedIn) {
                    CartScreen(graph, padding, snackbar, onCheckout = onCheckout)
                } else {
                    GuestGate("登录后查看购物车", requireLogin, padding)
                }
                else -> profilePane(padding)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    selected: Int,
    index: Int,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected == index,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
    )
}

@Composable
private fun GuestGate(message: String, requireLogin: () -> Unit, contentPadding: PaddingValues) {
    Box(
        Modifier.fillMaxSize().padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = requireLogin) { Text("登录 / 注册") }
        }
    }
}
