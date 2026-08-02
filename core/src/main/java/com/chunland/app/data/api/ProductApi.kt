package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.Category
import com.chunland.app.data.model.ProductDetail
import com.chunland.app.data.model.ProductListResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ProductApi {

    /** 服务端已过滤不可售/停店/过期商品；category 传 L1 会自动展开到子类；
     *  schemeCategory 是 方案视角过滤（与 category 互斥使用） */
    @GET("products")
    suspend fun list(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("category") category: String? = null,
        @Query("keyword") keyword: String? = null,
        @Query("merchant") merchant: Int? = null,
        @Query("schemeCategory") schemeCategory: Int? = null,
    ): ApiEnvelope<ProductListResponse>

    @GET("products/{code}")
    suspend fun detail(@Path("code") code: String): ApiEnvelope<ProductDetail>
}

interface CategoryApi {

    /** merchant 非空 → 商家自有分类空间（无自有分类返回空数组，绝不回落全局树冒充） */
    @GET("categories")
    suspend fun tree(@Query("merchant") merchant: Int? = null): ApiEnvelope<List<Category>>
}
