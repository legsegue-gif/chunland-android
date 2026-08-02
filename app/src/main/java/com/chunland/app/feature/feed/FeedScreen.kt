package com.chunland.app.feature.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chunland.app.core.AppGraph
import com.chunland.app.core.auth.LoginIntents
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.api.FeedApi
import com.chunland.app.data.model.FeedItem
import kotlinx.coroutines.launch

// 发现流：foryou 公开 / following 关注（需登录）。曝光埋点（/feed/events）后续增量。

class FeedViewModel(
    private val api: FeedApi,
    private val mode: String,
    /** 非空 = 单商家公开流（进店「动态」面），server 侧忽略 mode */
    private val merchantId: Int? = null,
) : ViewModel() {
    val items = mutableStateListOf<FeedItem>()
    var loading by mutableStateOf(false)
        private set
    var endReached by mutableStateOf(false)
        private set
    /** 翻页失败 → footer 显示「点击重试」，不再自动触发（对齐 iOS loadMoreFailed） */
    var loadFailed by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)

    private var cursor: String? = null

    /** 世代计数：refresh 递增作废 in-flight 响应（否则旧页数据会 append 进清空后的列表） */
    private var generation = 0

    init { loadMore() }

    fun loadMore() {
        if (loading || endReached) return
        val gen = generation
        viewModelScope.launch {
            loading = true
            loadFailed = false
            try {
                val page = apiCall { api.list(mode = mode, cursor = cursor, merchant = merchantId) }
                if (gen != generation) return@launch   // 期间被 refresh 作废
                items += page.items
                cursor = page.nextCursor
                endReached = page.nextCursor.isNullOrEmpty() || page.items.isEmpty()
            } catch (e: Exception) {
                if (gen == generation) {
                    loadFailed = true
                    toast = e.userMessage
                }
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    fun refresh() {
        generation++
        loading = false   // 释放旧 in-flight 的占位（其收尾已被世代检查隔离）
        cursor = null
        endReached = false
        items.clear()
        loadMore()
    }
}

@Composable
fun FeedScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
    requireLogin: () -> Unit,
    onOpenProduct: (String) -> Unit,
    onOpenFeedItem: (FeedItem) -> Unit,
    /** tab 重选信号（再点一次「发现」）：不在顶 → 回顶；已在顶 → 刷新（对齐 iOS TabRouter） */
    reselectSignal: Int = 0,
) {
    var mode by rememberSaveable { mutableStateOf("foryou") }
    val authState by graph.authManager.state.collectAsState()
    val followKeys by graph.followStore.keys.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(authState.isLoggedIn) {
        if (authState.isLoggedIn) graph.followStore.loadIfNeeded()
    }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            FilterChip(selected = mode == "foryou", onClick = { mode = "foryou" }, label = { Text("推荐") })
            FilterChip(selected = mode == "following", onClick = { mode = "following" }, label = { Text("关注") })
        }

        if (mode == "following" && !authState.isLoggedIn) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("登录后查看关注内容", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = requireLogin) { Text("登录 / 注册") }
                }
            }
            return@Column
        }

        // 每个 mode 独立 VM（key 区分），切换互不打扰
        val vm: FeedViewModel = viewModel(key = "feed-$mode") {
            FeedViewModel(graph.feedApi, mode)
        }
        val listState = remember(mode) { LazyListState() }

        // tab 重选：不在顶 → 平滑回顶；已在顶 → 刷新当前 mode。
        // 事件语义：信号是累计计数，切走再回时组合重建、LaunchedEffect 会以旧值首发 ——
        // 只响应「本次组合存续期间」的增量（> 进入时的初值），否则每次切回都误刷新
        val initialReselect = remember { reselectSignal }
        LaunchedEffect(reselectSignal) {
            if (reselectSignal > initialReselect) {
                if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                    listState.animateScrollToItem(0)
                } else {
                    vm.refresh()
                }
            }
        }

        LaunchedEffect(vm.toast) {
            vm.toast?.let { snackbar.showSnackbar(it); vm.toast = null }
        }
        // 关注集变化后进入关注流自动刷新一次（首次组合也会触发）。
        // 只看 channel/merchant 子集：product 收藏不进 feed 流，收藏个商品不该整刷丢滚动位置
        if (mode == "following") {
            val feedKeys = remember(followKeys) {
                followKeys.filterTo(mutableSetOf()) { !it.startsWith("product:") }
            }
            LaunchedEffect(feedKeys) { vm.refresh() }
        }

        // 周期冲刷埋点缓冲；离开页面时组合销毁自动停止
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                graph.feedEventTracker.flush()
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(vm.items, key = { it.id }) { item ->
                // 卡片进入组合 ≈ 曝光（tracker 进程内去重）
                LaunchedEffect(item.id) { graph.feedEventTracker.impression(item.id) }
                // dwell：卡片离开组合时上报本次可见时长（LazyColumn 离屏即 dispose，≈ 可见窗口）
                DisposableEffect(item.id) {
                    val start = System.currentTimeMillis()
                    onDispose {
                        graph.feedEventTracker.dwell(item.id, System.currentTimeMillis() - start)
                    }
                }
                val channelKey = item.channelId?.let { "channel:$it" }
                FeedCard(
                    item = item,
                    followed = channelKey?.let { it in followKeys },
                    // 分流对齐 iOS：商家可购卡 → 商品详情；内容卡 → 内容详情
                    onClick = {
                        graph.feedEventTracker.click(item.id)
                        val code = item.meta?.productCode
                        if (code != null) onOpenProduct(code) else onOpenFeedItem(item)
                    },
                    onToggleFollow = {
                        val id = item.channelId ?: return@FeedCard
                        val doToggle: () -> Unit = {
                            scope.launch {
                                graph.followStore.loadIfNeeded()
                                graph.followStore.toggle("channel", id.toString())
                                    ?.let { snackbar.showSnackbar(it) }
                            }
                        }
                        if (!authState.isLoggedIn) {
                            LoginIntents.set(doToggle)   // 登录成功自动续做该关注
                            requireLogin()
                        } else {
                            doToggle()
                        }
                    },
                )
            }
            if (!vm.endReached) {
                item {
                    if (vm.loadFailed) {
                        // 失败不自动重试（避免抖动环境下打转），点按恢复
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { vm.loadMore() }) { Text("加载失败，点击重试") }
                        }
                    } else {
                        LaunchedEffect(vm.items.size) { vm.loadMore() }
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            } else if (vm.items.isEmpty() && !vm.loading) {
                item {
                    Text(
                        if (mode == "following") "关注频道后这里会出现它们的内容" else "暂无内容",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
        }
    }
}

