package com.chunland.app.feature.stores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chunland.app.core.AppGraph
import com.chunland.app.data.model.FeedItem
import com.chunland.app.feature.feed.FeedCard
import com.chunland.app.feature.feed.FeedViewModel

/**
 * 进店页「动态」面（对齐 iOS StorePostsList）：该店公开动态流（feed?merchant=），
 * 复用发现流 FeedCard（无频道语境不显示关注按钮）与同一套埋点口径
 * （曝光/点击/dwell）。可购卡点进商品详情。
 */
@Composable
fun StorePostsList(
    graph: AppGraph,
    merchantId: Int,
    contentPadding: PaddingValues,
    onOpenProduct: (String) -> Unit,
    onOpenFeedItem: (FeedItem) -> Unit,
) {
    val vm: FeedViewModel = viewModel(key = "store-posts-$merchantId") {
        FeedViewModel(graph.feedApi, mode = "foryou", merchantId = merchantId)
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(vm.items, key = { it.id }) { item ->
            LaunchedEffect(item.id) { graph.feedEventTracker.impression(item.id) }
            DisposableEffect(item.id) {
                val start = System.currentTimeMillis()
                onDispose {
                    graph.feedEventTracker.dwell(item.id, System.currentTimeMillis() - start)
                }
            }
            FeedCard(
                item = item,
                followed = null,
                onClick = {
                    graph.feedEventTracker.click(item.id)
                    val code = item.meta?.productCode
                    if (code != null) onOpenProduct(code) else onOpenFeedItem(item)
                },
            )
        }
        if (!vm.endReached) {
            item {
                if (vm.loadFailed) {
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
                    "这家店还没有动态，发布新品、活动后会出现在这里",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }
    }
}
