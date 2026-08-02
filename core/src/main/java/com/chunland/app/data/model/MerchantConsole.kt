package com.chunland.app.data.model

import kotlinx.serialization.Serializable

// ---- 商家自助（自建商家）。零资金流：平台不代收/不结算货款给商家 ----

/** GET/PATCH /merchants/self —— 我的店铺（只会是 type='registered'） */
@Serializable
data class MyStore(
    val id: Int,
    val name: String,
    val type: String = "registered",
    val logoUrl: String? = null,
    /** 发货地区县 code（驱动距离定价；null → 距离费按 0 计） */
    val areaCode: String? = null,
    /** 起送金额（null 回退全局配置） */
    val minOrderAmount: Double? = null,
    val isActive: Boolean = true,
)

/** POST /merchants/self —— 开店（返回体与 /auth/roles 同构，解成 AuthResult） */
@Serializable
data class OpenStoreRequest(
    val name: String,
    val areaCode: String? = null,
)

/** PATCH /merchants/self —— 店铺设置。null 字段不发（explicitNulls=false）= 不改；不支持清空回退 */
@Serializable
data class UpdateStoreRequest(
    val name: String? = null,
    val areaCode: String? = null,
    val minOrderAmount: Double? = null,
)

// ---- 订单只读视图：不含买家地址/联系方式（隐私边界，服务端保证） ----

@Serializable
data class MerchantOrderSummary(
    val id: Int,
    val orderNumber: String,
    val status: String,
    /** 货值（平台费/代购费与商家无关，不下发） */
    val itemsTotal: Double,
    val itemCount: Int? = null,
    val createdAt: String = "",
)

@Serializable
data class MerchantOrderPage(
    val items: List<MerchantOrderSummary> = emptyList(),
)

@Serializable
data class MerchantOrderItem(
    val productCode: String,
    val name: String,
    val selectedSize: String? = null,
    val quantity: Int,
    val unitPrice: Double,
    val totalPrice: Double,
)

@Serializable
data class MerchantOrderDetail(
    val id: Int,
    val orderNumber: String,
    val status: String,
    val itemsTotal: Double,
    val createdAt: String = "",
    val items: List<MerchantOrderItem> = emptyList(),
)

// ---- 商品管理（管理视图，含已下架 purchasable=FALSE） ----

@Serializable
data class MerchantProduct(
    val code: String,
    val name: String,
    val description: String? = null,
    val price: Double? = null,
    val purchasable: Boolean = true,
    /** 尺码（鞋服可选） */
    val sizes: List<String>? = null,
    /** inStock | outOfStock（库存开关） */
    val stockStatus: String? = null,
    val thumbnail: String? = null,
    /** 相册 URL（[0] 为主图） */
    val gallery: List<String> = emptyList(),
)

@Serializable
data class MerchantProductPage(
    val items: List<MerchantProduct> = emptyList(),
)

@Serializable
data class CreateProductRequest(
    val name: String,
    val price: Double,
    val description: String? = null,
)

@Serializable
data class UpdateProductRequest(
    val name: String? = null,
    val price: Double? = null,
    val description: String? = null,
    val purchasable: Boolean? = null,
    val stockStatus: String? = null,
)

/** 图片上传返回（商品图/动态配图两步式）：key 供挂载/发布引用，url 供预览 */
@Serializable
data class UploadedImage(
    val key: String,
    val url: String,
)

/** PUT products/{code}/images —— 商品图整体替换，keys[0] 为主图；空数组 = 清空 */
@Serializable
data class SetProductImagesRequest(
    val keys: List<String>,
)

// ---- 店铺动态：自发图文进 feed_items，消费者在发现/关注流可见 ----

@Serializable
data class MerchantPostMedia(
    val url: String,
    val kind: String = "photo",
)

@Serializable
data class MerchantPost(
    val id: Int,
    val kind: String,             // text | photo | album
    val text: String? = null,
    val media: List<MerchantPostMedia> = emptyList(),
    /** 挂载的商品（可购卡，管理列表展示用） */
    val productCode: String? = null,
    val publishedAt: String = "",
)

@Serializable
data class MerchantPostPage(
    val items: List<MerchantPost> = emptyList(),
)

@Serializable
data class CreatePostRequest(
    val text: String? = null,
    val mediaKeys: List<String> = emptyList(),
    val productCode: String? = null,
)

// ---- 分类方案管理（lens 商家侧）。公开视图 DTO 在 Merchant.kt（CategoryScheme 复用）----

@Serializable
data class CreateSchemeRequest(
    val name: String,
)

/** null 字段不发 = 不改（explicitNulls=false） */
@Serializable
data class UpdateSchemeRequest(
    val name: String? = null,
    val isVisible: Boolean? = null,
    val isDefault: Boolean? = null,
)

@Serializable
data class NameRequest(
    val name: String,
)

/** POST schemes/{id}/categories：parentId 非空 = 建二级（最多两级，服务端校验） */
@Serializable
data class AddCategoryRequest(
    val name: String,
    val parentId: Int? = null,
)

/** POST schemes/{id}/categories 响应（建完立刻归类要用 id） */
@Serializable
data class CreatedCategory(
    val id: Int,
    val name: String = "",
)

/** GET/PUT scheme-categories/{catId}/products */
@Serializable
data class SchemeCategoryProducts(
    val id: Int,
    val productCodes: List<String> = emptyList(),
)

/** 整体替换（编辑器保存语义），assignedBy 缺省 manual */
@Serializable
data class SetCategoryProductsRequest(
    val codes: List<String>,
)

// ---- 经营数据（纯读）。GMV 口径 = 非取消/退款订单货值 ----

@Serializable
data class MerchantTopProduct(
    val name: String,
    val qty: Int,
)

@Serializable
data class MerchantStats(
    val totalOrders: Int = 0,
    val gmv: Double = 0.0,
    val last30dOrders: Int = 0,
    val last30dGmv: Double = 0.0,
    val topProducts: List<MerchantTopProduct> = emptyList(),
)
