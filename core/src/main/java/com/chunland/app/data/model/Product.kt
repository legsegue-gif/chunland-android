package com.chunland.app.data.model

import kotlinx.serialization.Serializable

// 价格字段：wire 是 JSON 数字（服务端 numeric 以 float 输出），
// 仅作展示 —— 一切金额计算走服务端（quote / 订单接口），端上绝不复算。

@Serializable
data class ProductSummary(
    val code: String,
    val name: String,
    val englishName: String? = null,
    val unitType: String? = null,
    val weight: Double? = null,
    val randomWeight: Boolean = false,
    val minOrderQuantity: Int = 1,
    val maxOrderQuantity: Int = 99,
    val currentPrice: Double? = null,
    val originalPrice: Double? = null,
    val pricePerUnit: Double? = null,
    val discountAmount: Double? = null,
    val stockStatus: String? = null,
    val thumbnail: String? = null,
) {
    val outOfStock: Boolean get() = stockStatus == "outOfStock"
}

@Serializable
data class Pagination(
    val page: Int,
    val limit: Int,
    val total: Int,
    val totalPages: Int,
)

/**
 * 符合当前筛选条件的价格分布，仅 withStats=true 时下发。
 *
 * 给 AI 用：知道区间才能判断「这个价位算便宜还是贵」，省一轮试探性查询。
 */
@Serializable
data class ProductStats(
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val avgPrice: Double? = null,
    val inStockCount: Int = 0,
)

@Serializable
data class ProductListResponse(
    val items: List<ProductSummary> = emptyList(),
    val pagination: Pagination,
    val stats: ProductStats? = null,
)

/** 用户画像片段（AI system prompt 注入用）。无可说内容时 fragment 为 null */
@Serializable
data class ProfileFragment(
    val fragment: String? = null,
)

@Serializable
data class ProductImage(
    val format: String,
    val galleryIndex: Int = 0,
    val url: String,
    val isPrimary: Boolean = false,
)

@Serializable
data class CategoryRef(
    val code: String,
    val name: String,
)

@Serializable
data class ProductDetail(
    val code: String,
    val name: String,
    val englishName: String? = null,
    val description: String? = null,
    val unitType: String? = null,
    val weight: Double? = null,
    val randomWeight: Boolean = false,
    val purchasable: Boolean = true,
    val sizes: List<String>? = null,
    val minOrderQuantity: Int = 1,
    val maxOrderQuantity: Int = 99,
    val merchantId: Int? = null,
    val merchantName: String? = null,
    val merchantContactable: Boolean? = null,
    val currentPrice: Double? = null,
    val originalPrice: Double? = null,
    val pricePerUnit: Double? = null,
    val discountAmount: Double? = null,
    val stockLevel: Int? = null,
    val stockStatus: String? = null,
    val images: List<ProductImage> = emptyList(),
    val categories: List<CategoryRef> = emptyList(),
) {
    val outOfStock: Boolean get() = stockStatus == "outOfStock"

    /** 内联图册用较轻的 product 格式（对齐 iOS：滑动省流量）；缺则回退非 thumbnail/全量 */
    val galleryImages: List<ProductImage>
        get() {
            val products = images.filter { it.format == "product" }.sortedBy { it.galleryIndex }
            if (products.isNotEmpty()) return products
            val nonThumb = images.filter { it.format != "thumbnail" }.sortedBy { it.galleryIndex }
            return nonThumb.ifEmpty { images }
        }

    val galleryUrls: List<String> get() = galleryImages.map { it.url }

    /** 全屏看图用更大的 zoom 渲染（按 galleryIndex 与内联图配对，缺则回退同位图） */
    val zoomUrls: List<String>
        get() {
            val zoomByIndex = images.filter { it.format == "zoom" }
                .associateBy({ it.galleryIndex }, { it.url })
            return galleryImages.map { zoomByIndex[it.galleryIndex] ?: it.url }
        }
}
