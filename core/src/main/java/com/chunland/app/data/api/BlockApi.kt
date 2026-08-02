package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.BlockRequest
import com.chunland.app.data.model.BlockedUserList
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface BlockApi {

    @GET("blocks")
    suspend fun list(): ApiEnvelope<BlockedUserList>

    /** 拉黑（入口在会话页，随 IM 落地；先备契约） */
    @POST("blocks")
    suspend fun block(@Body body: BlockRequest): ApiEnvelope<JsonElement>

    /** 解除拉黑（DELETE 语义走 POST /blocks/delete，对齐 iOS BlockService） */
    @POST("blocks/delete")
    suspend fun unblock(@Body body: BlockRequest): ApiEnvelope<JsonElement>
}
