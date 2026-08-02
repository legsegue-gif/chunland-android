package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.Address
import com.chunland.app.data.model.CheckoutBatch
import com.chunland.app.data.model.CreateAddressRequest
import com.chunland.app.data.model.DecideAdjustmentRequest
import com.chunland.app.data.model.OrderAdjustment
import com.chunland.app.data.model.OrderDetail
import com.chunland.app.data.model.OrderEvidence
import com.chunland.app.data.model.OrderQuote
import com.chunland.app.data.model.OrderSummary
import com.chunland.app.data.model.ProposeAdjustmentRequest
import com.chunland.app.data.model.PlaceOrderRequest
import com.chunland.app.data.model.QuoteRequest
import com.chunland.app.data.model.Region
import com.chunland.app.data.model.UpdateAddressRequest
import com.chunland.app.data.model.UpdateOrderStatusRequest
import kotlinx.serialization.json.JsonElement
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface AddressApi {

    @GET("addresses")
    suspend fun list(): ApiEnvelope<List<Address>>

    @POST("addresses")
    suspend fun create(@Body body: CreateAddressRequest): ApiEnvelope<Address>

    @PATCH("addresses/{id}")
    suspend fun update(
        @Path("id") id: Int,
        @Body body: UpdateAddressRequest,
    ): ApiEnvelope<Address>

    @DELETE("addresses/{id}")
    suspend fun delete(@Path("id") id: Int): ApiEnvelope<JsonElement>
}

interface RegionApi {

    /** 级联字典（公开）：parent 为空取省级，否则取直接子级（省→市→区县→街道） */
    @GET("regions")
    suspend fun children(@Query("parent") parent: String? = null): ApiEnvelope<List<Region>>
}

interface OrderApi {

    /** 报价 dry-run：选中商品 + 收货区县 → 含距离代购费的费用分解（不建单） */
    @POST("orders/quote")
    suspend fun quote(@Body body: QuoteRequest): ApiEnvelope<OrderQuote>

    /** 合并购物车结算，按商家拆单 */
    @POST("orders")
    suspend fun place(@Body body: PlaceOrderRequest): ApiEnvelope<CheckoutBatch>

    /** scope：hall=接单大厅（agent，服务区过滤）/ mine=自己接的单 / 默认=与我有关；
     *  sort=distance 仅 hall 有效（服务端全量后排） */
    @GET("orders")
    suspend fun list(
        @Query("status") status: String? = null,
        @Query("scope") scope: String? = null,
        @Query("sort") sort: String? = null,
    ): ApiEnvelope<List<OrderSummary>>

    /** 抢单（agent；不走通用 PATCH status） */
    @POST("orders/{id}/claim")
    suspend fun claim(@Path("id") id: Int): ApiEnvelope<OrderSummary>

    @GET("orders/{id}")
    suspend fun detail(@Path("id") id: Int): ApiEnvelope<OrderDetail>

    /** 只执行 availableActions 下发的转换（claim/pay/refund 不走此接口） */
    @PATCH("orders/{id}/status")
    suspend fun updateStatus(
        @Path("id") id: Int,
        @Body body: UpdateOrderStatusRequest,
    ): ApiEnvelope<JsonElement>

    // ---- 采购凭证：raw 二进制上传，图片经鉴权代理按 id 取 ----

    @GET("orders/{id}/evidence")
    suspend fun evidenceList(@Path("id") id: Int): ApiEnvelope<List<OrderEvidence>>

    /** 本单 agent 上传（body 为 image/jpeg|png|webp 原始字节，Content-Type 随 RequestBody） */
    @POST("orders/{id}/evidence")
    suspend fun uploadEvidence(
        @Path("id") id: Int,
        @Query("kind") kind: String = "receipt",
        @Body body: RequestBody,
    ): ApiEnvelope<OrderEvidence>

    /** 鉴权拉凭证图片 bytes（凭证桶非公开） */
    @Streaming
    @GET("orders/{id}/evidence/{eid}/raw")
    suspend fun evidenceImage(@Path("id") id: Int, @Path("eid") evidenceId: Int): ResponseBody

    // ---- 缺货改单：附属流，不走通用 PATCH status ----

    @GET("orders/{id}/adjustments")
    suspend fun adjustments(@Path("id") id: Int): ApiEnvelope<List<OrderAdjustment>>

    /** 本单 agent 发起（MVP 只支持下调：remove / reduce_qty） */
    @POST("orders/{id}/adjustments")
    suspend fun proposeAdjustment(
        @Path("id") id: Int,
        @Body body: ProposeAdjustmentRequest,
    ): ApiEnvelope<OrderAdjustment>

    /** 本单 consumer 接受/拒绝；accept 触发部分退款 */
    @POST("orders/{id}/adjustments/{adjId}/decide")
    suspend fun decideAdjustment(
        @Path("id") id: Int,
        @Path("adjId") adjustmentId: Int,
        @Body body: DecideAdjustmentRequest,
    ): ApiEnvelope<JsonElement>
}
