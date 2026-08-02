package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.PaymentInit
import kotlinx.serialization.json.JsonElement
import retrofit2.http.POST
import retrofit2.http.Path

/** 支付走专用端点（pay/refund 绝不走通用 PATCH status —— 状态机纪律） */
interface PaymentApi {

    /**
     * 发起支付：paid=true（Mock 模式）表示服务端已置 PAID，reload 即可；
     * 否则 orderStr 用于唤起支付宝 App。
     */
    @POST("payments/{orderId}")
    suspend fun create(@Path("orderId") orderId: Int): ApiEnvelope<PaymentInit>

    /** agent 对本单 PAID/PURCHASING 全额退款（对齐 iOS store.refund()；结果只用于触发 reload） */
    @POST("payments/{orderId}/refund")
    suspend fun refund(@Path("orderId") orderId: Int): ApiEnvelope<JsonElement>
}
