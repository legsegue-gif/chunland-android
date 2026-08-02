package com.chunland.app.data.model

import kotlinx.serialization.Serializable

/**
 * GET /merchants —— store picker 列表（公开展示数据）。
 * 费率/起送额为 null 表示走全局配置；distanceKm 由 server 按 anchor 计算（与下单报价
 * 同口径），**只作展示** —— 代购费一律以 POST orders/quote 为准，端上绝不本地算费。
 */
@Serializable
data class Merchant(
    val id: Int,
    val name: String,
    val type: String = "scraped",
    val logoUrl: String? = null,
    val platformFeeRate: Double? = null,
    val agentFeeRate: Double? = null,
    val minOrderAmount: Double? = null,
    val contactable: Boolean? = null,
    val areaCode: String? = null,
    val areaName: String? = null,
    val cityCode: String? = null,
    val cityName: String? = null,
    val distanceKm: Double? = null,
)

/** GET /merchants 回显的定位锚点（anchor 参数解析结果），可为省/市/区县任一级 */
@Serializable
data class MerchantListAnchor(
    val code: String,
    val name: String? = null,
    val level: Int? = null,
    val cityCode: String? = null,
    val cityName: String? = null,
)

@Serializable
data class MerchantListResponse(
    val anchor: MerchantListAnchor? = null,
    val items: List<Merchant> = emptyList(),
)

// ---- 分类方案（lens）：商家自定义的进店浏览视角，与官方分类互斥使用 ----

@Serializable
data class SchemeCategory(
    val id: Int,
    val name: String,
    val productCount: Int? = null,
    /** 两级：一级带二级列表（二级恒空；旧响应缺省空） */
    val children: List<SchemeCategory> = emptyList(),
)

@Serializable
data class CategoryScheme(
    val id: Int,
    val name: String,
    val origin: String? = null,
    val isVisible: Boolean? = null,
    val isDefault: Boolean = false,
    val categories: List<SchemeCategory> = emptyList(),
)

@Serializable
data class CategorySchemePage(
    val items: List<CategoryScheme> = emptyList(),
)
