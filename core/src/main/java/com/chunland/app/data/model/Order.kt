package com.chunland.app.data.model

import kotlinx.serialization.Serializable

// 金额字段 wire 是数字（四层 service 手工对象），仅展示 —— 计费唯一来源是服务端
// 服务端计费，报价一律 POST orders/quote，端上绝不复算。

/**
 * 服务端 orderStateMachine 下发的可执行动作。
 * UI 按此渲染按钮 —— **绝不在端上硬编码状态转换**（iOS 同款铁律）。
 */
@Serializable
data class OrderAction(
    val action: String,     // claim / cancel / startPurchase / startDeliver / confirmDelivery / complete
    val toStatus: String,   // 配合 PATCH orders/{id}/status
    val label: String,      // 服务端下发的按钮文案
    val style: String = "secondary",  // primary / destructive / secondary
)

@Serializable
data class OrderSummary(
    val id: Int,
    val orderNumber: String,
    val status: String,
    val merchantId: Int? = null,
    val merchantName: String? = null,
    val itemsTotal: Double = 0.0,
    val platformFee: Double = 0.0,
    val agentFee: Double = 0.0,
    val totalAmount: Double = 0.0,
    val tipAmount: Double? = null,
    val itemCount: Int? = null,
    val createdAt: String = "",
    val availableActions: List<OrderAction>? = null,
    val distanceKm: Double? = null,
    val onTheWay: Boolean? = null,
    val detourKm: Double? = null,
    val firstProductName: String? = null,
    val firstThumbnail: String? = null,
    val hasReceipt: Boolean? = null,
)

/** POST /orders —— 合并购物车结算，按商家拆单后的批次（单商家时 orders.size == 1） */
@Serializable
data class CheckoutBatch(
    val orders: List<OrderSummary> = emptyList(),
    val orderCount: Int = 0,
    val grandTotal: String = "",
)

/** POST /orders/quote（dry-run 不建单）：含距离驱动代购费的费用分解 */
@Serializable
data class OrderQuote(
    val groups: List<QuoteGroup> = emptyList(),
    val grandTotal: Double = 0.0,
)

@Serializable
data class QuoteGroup(
    val merchantId: Int,
    val merchantName: String = "",
    val itemsTotal: Double = 0.0,
    val platformFee: Double = 0.0,
    val agentFee: Double = 0.0,
    val totalAmount: Double = 0.0,
    val meetsMinOrder: Boolean = true,
    val minOrderAmount: Double = 0.0,
    val platformFeeRate: Double = 0.0,
)

@Serializable
data class OrderDetail(
    val id: Int,
    val orderNumber: String,
    val status: String,
    val consumerId: Int,
    val agentId: Int? = null,
    val merchantId: Int? = null,
    val itemsTotal: Double = 0.0,
    val platformFee: Double = 0.0,
    val agentFee: Double = 0.0,
    val totalAmount: Double = 0.0,
    val deliveryAddress: DeliveryAddress,
    val deliveryNote: String? = null,
    val items: List<OrderItem> = emptyList(),
    val createdAt: String = "",
    val claimedAt: String? = null,
    val completedAt: String? = null,
    val availableActions: List<OrderAction>? = null,
)

@Serializable
data class OrderItem(
    val id: Int,
    val productCode: String,
    val selectedSize: String? = null,
    val quantity: Int,
    val unitPrice: Double = 0.0,
    val totalPrice: Double = 0.0,
    val productSnapshot: ProductSnapshot,
)

/** 下单时冻结的商品快照（防后续改价影响历史订单展示） */
@Serializable
data class ProductSnapshot(
    val code: String,
    val name: String,
    val price: Double? = null,
    val randomWeight: Boolean? = null,
)

@Serializable
data class QuoteRequest(
    val areaCode: String?,
    val productCodes: List<String>? = null,
)

@Serializable
data class PlaceOrderRequest(
    val deliveryAddress: DeliveryAddress,
    val productCodes: List<String>? = null,
    val deliveryNote: String? = null,
    val remark: String? = null,
)

@Serializable
data class UpdateOrderStatusRequest(
    val status: String,
)

/** POST /payments/{orderId} 响应：paid=true（Mock 立即支付）；否则 orderStr 唤起支付宝 */
@Serializable
data class PaymentInit(
    val paid: Boolean,
    val orderStr: String? = null,
)

/**
 * 采购凭证。凭证桶非公开：图片必须经鉴权代理按 id 拉 bytes
 * （GET orders/{id}/evidence/{eid}/raw），绝不当公开 URL 用。
 */
@Serializable
data class OrderEvidence(
    val id: Int,
    val orderId: Int,
    val kind: String = "receipt",   // receipt(小票) / product_photo(实物) / dispute(纠纷举证)
    val uploadedBy: Int = 0,
    val createdAt: String = "",
    val url: String = "",
)

// ---- 缺货改单：附属流，主状态留 PURCHASING；金额变更走专用端点含部分退款 ----

@Serializable
data class AdjustmentItem(
    val orderItemId: Int,
    val action: String,             // remove / reduce_qty / change_price / change_spec
    val newQuantity: Int? = null,
    val newUnitPrice: Double? = null,
    val newSize: String? = null,
    val note: String? = null,
)

@Serializable
data class AdjustmentDetail(
    val items: List<AdjustmentItem> = emptyList(),
)

@Serializable
data class OrderAdjustment(
    val id: Int,
    val orderId: Int,
    val proposedBy: Int = 0,
    val kind: String = "out_of_stock",
    val detail: AdjustmentDetail = AdjustmentDetail(),
    val amountDelta: Double = 0.0,  // 负 = 下调，accept 触发部分退款
    val status: String = "PENDING", // PENDING / ACCEPTED / REJECTED / EXPIRED
    val decidedBy: Int? = null,
    val decidedAt: String? = null,
    val note: String? = null,
    val createdAt: String = "",
)

@Serializable
data class ProposeAdjustmentRequest(
    val kind: String,
    val detail: AdjustmentDetail,
    val note: String? = null,
)

@Serializable
data class DecideAdjustmentRequest(
    val accept: Boolean,
    val reason: String? = null,
)

fun adjustmentStatusLabel(status: String): String = when (status) {
    "PENDING" -> "待买家确认"
    "ACCEPTED" -> "已接受"
    "REJECTED" -> "已拒绝"
    "EXPIRED" -> "已过期"
    else -> status
}

/** 订单状态中文标签（仅展示；状态流转一律走 availableActions） */
fun orderStatusLabel(status: String): String = when (status) {
    "PENDING" -> "待接单"
    "CLAIMED" -> "已接单·待支付"
    "PAID" -> "已支付"
    "PURCHASING" -> "采购中"
    "DELIVERING" -> "配送中"
    "DELIVERED" -> "已送达"
    "COMPLETED" -> "已完成"
    "CANCELLED" -> "已取消"
    "REFUNDED" -> "已退款"
    else -> status
}
