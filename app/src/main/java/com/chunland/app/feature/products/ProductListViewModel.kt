package com.chunland.app.feature.products

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.api.CategoryApi
import com.chunland.app.data.api.ProductApi
import com.chunland.app.data.model.Category
import com.chunland.app.data.model.ProductSummary
import kotlinx.coroutines.launch

class ProductListViewModel(
    private val productApi: ProductApi,
    private val categoryApi: CategoryApi,
    /** 非 null = 进店视角（对齐 iOS StoreView：列表只含该店商品） */
    private val merchantId: Int? = null,
) : ViewModel() {

    val items = mutableStateListOf<ProductSummary>()
    var categories by mutableStateOf<List<Category>>(emptyList())
        private set
    var keyword by mutableStateOf("")
    var activeCategory by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var endReached by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)

    private var page = 0
    private var schemeCategoryId: Int? = null

    /** 世代计数：refresh 递增作废 in-flight 响应（防旧页 append 进新筛选的列表） */
    private var generation = 0

    /** 方案视角过滤（进店页 lens 驱动；与官方 category 互斥） */
    fun setSchemeCategory(id: Int?) {
        if (id == schemeCategoryId) return
        schemeCategoryId = id
        refresh()
    }

    /** 官方分类过滤（进店页 sidebar/分类 chips 驱动，幂等直设；toggle 语义见 selectCategory） */
    fun setCategory(code: String?) {
        if (code == activeCategory) return
        activeCategory = code
        refresh()
    }

    init {
        refresh()
        // 全局分类树只在全目录视角加载喂内部 chips；进店视角的官方分类树由 StoreScreen
        // 自己加载（sidebar / 分类 chips，对齐 iOS StoreView），经 setCategory 传入过滤
        if (merchantId == null) {
            viewModelScope.launch {
                // 分类加载失败不打断商品列表，静默留空即可
                runCatching { categories = apiCall { categoryApi.tree() } }
            }
        }
    }

    fun refresh() {
        generation++
        loading = false   // 释放旧 in-flight 的占位（其收尾已被世代检查隔离）
        page = 0
        endReached = false
        items.clear()
        loadMore()
    }

    fun loadMore() {
        if (loading || endReached) return
        val gen = generation
        viewModelScope.launch {
            loading = true
            try {
                val result = apiCall {
                    productApi.list(
                        page = page + 1,
                        limit = 20,
                        category = activeCategory,
                        keyword = keyword.trim().ifBlank { null },
                        merchant = merchantId,
                        schemeCategory = schemeCategoryId,
                    )
                }
                if (gen != generation) return@launch   // 期间被 refresh 作废
                page = result.pagination.page
                items += result.items
                endReached = page >= result.pagination.totalPages || result.items.isEmpty()
            } catch (e: Exception) {
                if (gen == generation) toast = e.userMessage
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    fun submitSearch() = refresh()

    /** 再点一次当前选中的分类 = 取消筛选 */
    fun selectCategory(code: String?) {
        activeCategory = if (activeCategory == code) null else code
        refresh()
    }
}
