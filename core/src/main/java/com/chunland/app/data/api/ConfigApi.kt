package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.CheckoutConfig
import retrofit2.http.GET
import retrofit2.http.Query

interface ConfigApi {

    /** 公开 checkout 配置（费率/起送金额，仅展示用）。带 merchant 时 NULL 字段回退全局值。 */
    @GET("config/checkout")
    suspend fun checkout(@Query("merchant") merchant: Int? = null): ApiEnvelope<CheckoutConfig>
}
