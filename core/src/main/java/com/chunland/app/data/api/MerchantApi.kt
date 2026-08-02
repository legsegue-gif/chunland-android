package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.CategorySchemePage
import com.chunland.app.data.model.MerchantListResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface MerchantApi {

    /** 公开 store picker；anchor = 用户所在市/区县 region code（可选，传了才有 distanceKm） */
    @GET("merchants")
    suspend fun list(@Query("anchor") anchor: String? = null): ApiEnvelope<MerchantListResponse>

    /** 进店分类方案（lens，公开，仅可见方案） */
    @GET("merchants/{id}/schemes")
    suspend fun schemes(@Path("id") merchantId: Int): ApiEnvelope<CategorySchemePage>
}
