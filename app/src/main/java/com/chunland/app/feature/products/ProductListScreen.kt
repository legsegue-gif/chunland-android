package com.chunland.app.feature.products

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.absoluteMediaUrl
import com.chunland.app.data.model.ProductSummary
import com.chunland.app.ui.formatPrice

@Composable
fun ProductListScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
    onOpenProduct: (String) -> Unit,
    merchantId: Int? = null,
    schemeCategoryId: Int? = null,
    /** 官方分类过滤（进店页 sidebar/chips 驱动，对齐 iOS StoreView；与 schemeCategoryId 互斥由调用方保证） */
    categoryCode: String? = null,
) {
    val vm: ProductListViewModel = viewModel {
        ProductListViewModel(graph.productApi, graph.categoryApi, merchantId)
    }

    LaunchedEffect(schemeCategoryId) { vm.setSchemeCategory(schemeCategoryId) }
    LaunchedEffect(categoryCode) { vm.setCategory(categoryCode) }

    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            snackbar.showSnackbar(it)
            vm.toast = null
        }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedTextField(
            value = vm.keyword,
            onValueChange = { vm.keyword = it },
            placeholder = { Text("搜索商品") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submitSearch() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (vm.categories.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                lazyRowItems(vm.categories, key = { it.code }) { category ->
                    FilterChip(
                        selected = vm.activeCategory == category.code,
                        onClick = { vm.selectCategory(category.code) },
                        label = { Text(category.name) },
                    )
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(vm.items, key = { it.code }) { product ->
                ProductCard(product) { onOpenProduct(product.code) }
            }

            if (!vm.endReached) {
                // 触底加载：footer 进入组合即拉下一页
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LaunchedEffect(vm.items.size) { vm.loadMore() }
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (vm.items.isEmpty() && !vm.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "没有找到商品",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductCard(product: ProductSummary, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column {
            AsyncImage(
                model = absoluteMediaUrl(product.thumbnail),
                contentDescription = product.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            Column(Modifier.padding(10.dp)) {
                Text(
                    product.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    product.currentPrice?.let {
                        Text(
                            "¥${formatPrice(it)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    val original = product.originalPrice
                    if (product.discountAmount != null && original != null) {
                        Text(
                            "¥${formatPrice(original)}",
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = TextDecoration.LineThrough,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (product.outOfStock) {
                    Text(
                        "缺货",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
