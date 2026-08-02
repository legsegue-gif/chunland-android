package com.chunland.app.feature.checkout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.userMessage
import com.chunland.app.data.api.AddressApi
import com.chunland.app.data.api.OrderApi
import com.chunland.app.data.model.Address
import com.chunland.app.data.model.CheckoutBatch
import com.chunland.app.data.model.OrderQuote
import com.chunland.app.data.model.PlaceOrderRequest
import com.chunland.app.data.model.QuoteRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CheckoutViewModel(
    private val addressApi: AddressApi,
    private val orderApi: OrderApi,
) : ViewModel() {

    var addresses by mutableStateOf<List<Address>>(emptyList())
        private set
    var selected by mutableStateOf<Address?>(null)
        private set
    var quote by mutableStateOf<OrderQuote?>(null)
        private set
    var quoting by mutableStateOf(false)
        private set
    var submitting by mutableStateOf(false)
        private set
    /** 下单成功的批次；非 null 触发跳转订单列表 */
    var placed by mutableStateOf<CheckoutBatch?>(null)
        private set
    var toast by mutableStateOf<String?>(null)

    init {
        // 停留结算页期间后台每 4s 探测费率变化（对齐 iOS）：变了提示并刷新，不静默改显示价
        viewModelScope.launch {
            while (true) {
                delay(4_000)
                pollQuote()
            }
        }
    }

    private suspend fun pollQuote() {
        val address = selected ?: return
        val current = quote ?: return
        if (quoting || submitting || placed != null) return
        val fresh = runCatching {
            apiCall {
                orderApi.quote(QuoteRequest(areaCode = address.areaCode, productCodes = CheckoutDraft.productCodes))
            }
        }.getOrNull() ?: return   // 探测失败静默（下一轮再试），不打扰用户
        if (fresh.grandTotal != current.grandTotal) {
            quote = fresh
            toast = "费率已更新，合计已刷新"
        }
    }

    /** 每次进入结算页都重拉（新增地址返回后自动刷新并选中默认/最新） */
    fun loadAddresses() {
        viewModelScope.launch {
            try {
                val list = apiCall { addressApi.list() }
                addresses = list
                val keep = selected?.let { sel -> list.firstOrNull { it.id == sel.id } }
                selected = keep ?: list.firstOrNull { it.isDefault } ?: list.firstOrNull()
                requote()
            } catch (e: Exception) {
                toast = e.userMessage
            }
        }
    }

    fun select(address: Address) {
        selected = address
        requote()
    }

    /** 报价一律来自服务端（距离代购费按收货区县算），端上绝不复算。勾选结算只带已选 codes。 */
    private fun requote() {
        val address = selected ?: run { quote = null; return }
        viewModelScope.launch {
            quoting = true
            try {
                quote = apiCall {
                    orderApi.quote(QuoteRequest(areaCode = address.areaCode, productCodes = CheckoutDraft.productCodes))
                }
            } catch (e: Exception) {
                quote = null
                toast = e.userMessage
            } finally {
                quoting = false
            }
        }
    }

    fun submit() {
        val address = selected ?: run { toast = "请先选择收货地址"; return }
        if (submitting) return
        viewModelScope.launch {
            submitting = true
            try {
                placed = apiCall {
                    orderApi.place(
                        PlaceOrderRequest(
                            deliveryAddress = address.snapshot(),
                            productCodes = CheckoutDraft.productCodes,
                        ),
                    )
                }
            } catch (e: Exception) {
                toast = e.userMessage
            } finally {
                submitting = false
            }
        }
    }
}
