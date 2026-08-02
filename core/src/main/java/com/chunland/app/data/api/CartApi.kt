package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.AddCartItemRequest
import com.chunland.app.data.model.Cart
import com.chunland.app.data.model.CartMutation
import com.chunland.app.data.model.UpdateCartItemRequest
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface CartApi {

    @GET("cart")
    suspend fun get(): ApiEnvelope<Cart>

    @POST("cart/items")
    suspend fun addItem(@Body body: AddCartItemRequest): ApiEnvelope<CartMutation>

    /** 尺码商品经 body.selectedSize 定位具体行 */
    @PATCH("cart/items/{productCode}")
    suspend fun updateItem(
        @Path("productCode") productCode: String,
        @Body body: UpdateCartItemRequest,
    ): ApiEnvelope<CartMutation>

    /** data:null → 调用方走 apiCallUnit */
    @DELETE("cart/items/{productCode}")
    suspend fun removeItem(
        @Path("productCode") productCode: String,
        @Query("size") size: String? = null,
    ): ApiEnvelope<JsonElement>

    @DELETE("cart")
    suspend fun clear(): ApiEnvelope<JsonElement>
}
