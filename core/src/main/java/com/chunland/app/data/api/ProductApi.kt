package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.Category
import com.chunland.app.data.model.ProductDetail
import com.chunland.app.data.model.ProductListResponse
import com.chunland.app.data.model.ProfileFragment
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ProductApi {

    /**
     * 服务端已过滤不可售/停店/过期商品；category 传 L1 会自动展开到子类；
     * schemeCategory 是方案视角过滤（与 category 互斥使用）。
     *
     * 后半段参数是给 AI 的结构化查询能力 —— 模型能一次问对
     * 「100 元以内、有货、按价格从低到高」，不必翻页人工筛：
     * - priceMin/priceMax 价格区间（服务端会纠正倒置的区间）
     * - inStock 只看有货
     * - sort: relevance(默认) / price_asc / price_desc / discount / newest
     * - fields=compact 精简字段（省模型上下文）
     * - withStats 附带价格区间统计
     *
     * 服务端对越界参数一律静默归一化，不会因为一个 limit=1000 就报错。
     */
    @GET("products")
    suspend fun list(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("category") category: String? = null,
        @Query("keyword") keyword: String? = null,
        @Query("merchant") merchant: Int? = null,
        @Query("schemeCategory") schemeCategory: Int? = null,
        @Query("priceMin") priceMin: Double? = null,
        @Query("priceMax") priceMax: Double? = null,
        @Query("inStock") inStock: Boolean? = null,
        @Query("sort") sort: String? = null,
        @Query("fields") fields: String? = null,
        @Query("withStats") withStats: Boolean? = null,
    ): ApiEnvelope<ProductListResponse>

    /**
     * 当前用户的画像片段（默认收货地区 + 近三个月常买品类），供拼进 AI 的 system prompt。
     *
     * 只聚合用户自己已有的数据，不新增采集。没有可说的内容时 fragment 为空 ——
     * 调用方不注入，而不是注入一句「暂无信息」白占 token。
     */
    @GET("products/profile-fragment")
    suspend fun profileFragment(): ApiEnvelope<ProfileFragment>

    @GET("products/{code}")
    suspend fun detail(@Path("code") code: String): ApiEnvelope<ProductDetail>
}

interface CategoryApi {

    /**
     * merchant 非空 → 商家自有分类空间（无自有分类返回空数组，绝不回落全局树冒充）。
     *
     * withCounts 给 AI 用：一并返回每个分类的在售商品数（父节点含其子节点），
     * 模型就不会推荐一个空分类再查一次发现没货。
     */
    @GET("categories")
    suspend fun tree(
        @Query("merchant") merchant: Int? = null,
        @Query("withCounts") withCounts: Boolean? = null,
    ): ApiEnvelope<List<Category>>
}
