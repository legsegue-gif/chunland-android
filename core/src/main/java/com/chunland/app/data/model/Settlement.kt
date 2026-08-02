package com.chunland.app.data.model

import kotlinx.serialization.Serializable

/**
 * 资金结算（阶段0 纯记账）：订单完成后生成应结算账（货款返还 + 代购费），
 * 实际打款为 NullPayout 占位。agent 只读视图。
 */
@Serializable
data class Settlement(
    val id: Int,
    val orderId: Int,
    val orderNumber: String,
    /** 货款返还（代购垫付的商品货值） */
    val itemsReimburse: Double,
    /** 代购费（劳务） */
    val agentFee: Double,
    /** 平台费（从代购费中扣，仅展示口径） */
    val platformFee: Double,
    /** 应得合计 */
    val netPayable: Double,
    val status: String,       // PENDING | PAYABLE | PAID | VOID
    val paidAt: String? = null,
    val createdAt: String = "",
)

@Serializable
data class SettlementSummary(
    val items: List<Settlement> = emptyList(),
    val pendingTotal: Double = 0.0,
    val paidTotal: Double = 0.0,
)

fun settlementStatusLabel(status: String): String = when (status) {
    "PENDING" -> "待结算"
    "PAYABLE" -> "可结算"
    "PAID" -> "已结算"
    "VOID" -> "已冲销"
    else -> status
}
