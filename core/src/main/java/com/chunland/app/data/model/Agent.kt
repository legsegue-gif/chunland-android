package com.chunland.app.data.model

import kotlinx.serialization.Serializable

// 代购人工作台聚合（对齐 iOS Models/Agent.swift）。
// wire 为四层 service 手工对象（camelCase、金额数字）。

/** GET /agent-profile/dashboard —— 待办计数 + 收入汇总（分组是展示聚合，转换仍由 availableActions 驱动） */
@Serializable
data class AgentDashboard(
    val counts: Counts = Counts(),
    val earnings: Earnings = Earnings(),
) {
    @Serializable
    data class Counts(
        val claimed: Int = 0,          // 待买家支付
        val paid: Int = 0,             // 待采购
        val purchasing: Int = 0,       // 采购中
        val delivering: Int = 0,       // 配送中
        val delivered: Int = 0,        // 待买家确认
        val purchasingNoReceipt: Int = 0,          // 采购中·缺小票凭证
        val purchasingPendingAdjustment: Int = 0,  // 采购中·改单待买家答复
    )

    @Serializable
    data class Earnings(
        val today: Double = 0.0,             // 今日已记账收入（非 VOID）
        val month: Double = 0.0,             // 本月已记账收入
        val pendingSettlement: Double = 0.0, // 待结算余额（PENDING+PAYABLE）
    )
}

/** GET /agent-profile/purchase-list —— 待采购/采购中订单按商家分组，同商品（code+尺码）跨单聚合数量 */
@Serializable
data class PurchaseList(
    val groups: List<Group> = emptyList(),
) {
    @Serializable
    data class OrderRef(
        val id: Int,
        val orderNumber: String,
        val status: String = "",        // PAID / PURCHASING
        val hasReceipt: Boolean = false,
    )

    @Serializable
    data class BreakdownEntry(
        val orderId: Int = 0,
        val orderNumber: String = "",
        val quantity: Int = 0,
    )

    @Serializable
    data class Item(
        val productCode: String = "",
        val name: String,
        val selectedSize: String? = null,
        val imageUrl: String? = null,
        val totalQuantity: Int = 0,
        val breakdown: List<BreakdownEntry> = emptyList(),
    )

    @Serializable
    data class Group(
        val merchantId: Int,
        val merchantName: String = "",
        val orders: List<OrderRef> = emptyList(),
        val items: List<Item> = emptyList(),
    )
}

/** GET/PATCH /agent-profile —— 代购人资料（接单开关/服务区县/简介 + 只读统计） */
@Serializable
data class AgentProfile(
    val userId: Int = 0,
    val bio: String? = null,
    val isAvailable: Boolean = true,
    val serviceAreaCodes: List<String> = emptyList(),
    val rating: Double = 0.0,
    val totalOrders: Int = 0,
)

/** PATCH 部分更新：不发的字段不改（后代购费平台统一定价，serviceFee 不再提交） */
@Serializable
data class UpdateAgentProfileRequest(
    val bio: String? = null,
    val isAvailable: Boolean,
    val serviceAreaCodes: List<String>,
)
