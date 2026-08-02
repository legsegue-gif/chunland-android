package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.CreateReportRequest
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.POST

interface ReportApi {

    /** 提交举报（需登录）。响应 data 是举报回执行，端上不消费。 */
    @POST("reports")
    suspend fun create(@Body body: CreateReportRequest): ApiEnvelope<JsonElement>
}
