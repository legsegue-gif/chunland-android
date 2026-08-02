package com.chunland.app.data.model

import kotlinx.serialization.Serializable

// Model A：购物车 per-user 跨商家，items 带 merchantId/merchantName 供 UI 分组。
// itemsTotal 是服务端算好的字符串 —— 端上只显示，绝不本地加总。

@Serializable
data class Cart(
    val cartId: Int,
    val items: List<CartItem> = emptyList(),
    val itemsTotal: String = "0",
)

@Serializable
data class CartItem(
    val id: Int,
    val productCode: String,
    val selectedSize: String? = null,
    val quantity: Int,
    val name: String,
    val merchantId: Int,
    val merchantName: String = "",
    val unitType: String? = null,
    val randomWeight: Boolean = false,
    val minOrderQuantity: Int? = null,
    val maxOrderQuantity: Int? = null,
    val currentPrice: Double? = null,
    val originalPrice: Double? = null,
    val stockStatus: String? = null,
    val thumbnail: String? = null,
)

@Serializable
data class CartMutation(
    val id: Int,
    val quantity: Int,
)

@Serializable
data class AddCartItemRequest(
    val productCode: String,
    val quantity: Int,
    val selectedSize: String? = null,
)

@Serializable
data class UpdateCartItemRequest(
    val quantity: Int,
    val selectedSize: String? = null,
)
