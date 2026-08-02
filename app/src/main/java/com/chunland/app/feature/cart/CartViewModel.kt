package com.chunland.app.feature.cart

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.apiCallUnit
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.api.CartApi
import com.chunland.app.data.api.ConfigApi
import com.chunland.app.data.model.Cart
import com.chunland.app.data.model.CartItem
import com.chunland.app.data.model.UpdateCartItemRequest
import kotlinx.coroutines.launch

class CartViewModel(
    private val api: CartApi,
    private val configApi: ConfigApi,
) : ViewModel() {

    var cart by mutableStateOf<Cart?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)

    /** 勾选结算（对齐 iOS CartStore.selected）：重载时默认全选可购项 */
    var selected by mutableStateOf(setOf<Int>())
        private set

    /** merchantId → 有效起送金额（服务端 config 端点已做 NULL 回退全局），仅作结算前提示 */
    var minOrders by mutableStateOf<Map<Int, Double>>(emptyMap())
        private set

    val selectableIds: Set<Int>
        get() = cart?.items.orEmpty().filter { it.stockStatus != "outOfStock" }.map { it.id }.toSet()

    val allSelected: Boolean
        get() = selectableIds.isNotEmpty() && selected.containsAll(selectableIds)

    val selectedItems: List<CartItem>
        get() = cart?.items.orEmpty().filter { it.id in selected }

    /** 已选商品货值小计 —— 仅展示与起送校验；费率/代购费一律以结算页服务端 quote 为准 */
    val selectedItemsTotal: Double
        get() = selectedItems.sumOf { (it.currentPrice ?: 0.0) * it.quantity }

    /** 已选中但未满起送的商家名（对齐 iOS blockingGroups） */
    val blockingMerchants: List<String>
        get() = selectedItems.groupBy { it.merchantId }.mapNotNull { (mid, items) ->
            val min = minOrders[mid] ?: return@mapNotNull null
            val sub = items.sumOf { (it.currentPrice ?: 0.0) * it.quantity }
            if (sub < min) items.first().merchantName else null
        }

    val canCheckout: Boolean
        get() = selected.isNotEmpty() && blockingMerchants.isEmpty()

    fun toggle(item: CartItem) {
        if (item.stockStatus == "outOfStock") return
        selected = if (item.id in selected) selected - item.id else selected + item.id
    }

    fun toggleSelectAll() {
        selected = if (allSelected) emptySet() else selectableIds
    }

    fun reload() {
        viewModelScope.launch {
            loading = true
            try {
                cart = apiCall { api.get() }
                selected = selectableIds   // 重载默认全选可购项（对齐 iOS reload 种子）
                loadMinOrders()
            } catch (e: Exception) {
                toast = e.userMessage
            } finally {
                loading = false
            }
        }
    }

    /** 数量步进：本地乐观改数，成功后静默拉全量（合计金额只认服务端），失败回滚。 */
    fun changeQuantity(item: CartItem, newQuantity: Int) {
        val snapshot = cart ?: return
        val target = newQuantity.coerceIn(item.minOrderQuantity ?: 1, item.maxOrderQuantity ?: Int.MAX_VALUE)
        if (target == item.quantity) return
        cart = snapshot.copy(
            items = snapshot.items.map { if (it.id == item.id) it.copy(quantity = target) else it }
        )
        viewModelScope.launch {
            try {
                apiCall { api.updateItem(item.productCode, UpdateCartItemRequest(target, item.selectedSize)) }
                refreshSilently()
            } catch (e: Exception) {
                cart = snapshot
                toast = e.userMessage
            }
        }
    }

    fun remove(item: CartItem) {
        val snapshot = cart ?: return
        val wasSelected = item.id in selected
        cart = snapshot.copy(items = snapshot.items.filterNot { it.id == item.id })
        selected = selected - item.id
        viewModelScope.launch {
            try {
                apiCallUnit { api.removeItem(item.productCode, item.selectedSize) }
                refreshSilently()
            } catch (e: Exception) {
                cart = snapshot
                if (wasSelected) selected = selected + item.id   // 回滚连同勾选态
                toast = e.userMessage
            }
        }
    }

    private suspend fun refreshSilently() {
        runCatching {
            cart = apiCall { api.get() }
            // 保留用户勾选，仅剔除已不存在/已缺货的行
            selected = selected intersect selectableIds
        }
    }

    /** 起送金额按购物车内商家逐个取（低频小请求，失败静默——服务端下单时仍会兜底校验） */
    private fun loadMinOrders() {
        val ids = cart?.items.orEmpty().map { it.merchantId }.distinct()
        viewModelScope.launch {
            val map = mutableMapOf<Int, Double>()
            ids.forEach { id ->
                runCatching { apiCall { configApi.checkout(id) } }
                    .onSuccess { map[id] = it.minOrderAmount }
            }
            minOrders = map
        }
    }
}
