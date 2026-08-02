package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.FeedEventsRequest
import com.chunland.app.data.model.FeedPage
import com.chunland.app.data.model.FollowDetailList
import com.chunland.app.data.model.FollowRequest
import com.chunland.app.data.model.FollowsResponse
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Query

interface FeedApi {

    /** mode=foryou 公开 / following 需登录；cursor 为 base64url，原样回传 */
    @GET("feed")
    suspend fun list(
        @Query("mode") mode: String = "foryou",
        @Query("limit") limit: Int = 20,
        @Query("cursor") cursor: String? = null,
        @Query("merchant") merchant: Int? = null,
    ): ApiEnvelope<FeedPage>

    // ---- 关注/收藏（需登录）----

    @GET("feed/follows")
    suspend fun follows(): ApiEnvelope<FollowsResponse>

    /** 管理页：关注关系 + 展示数据（名/图/价/有效态），按关注时间倒序 */
    @GET("feed/follows/detail")
    suspend fun followsDetail(): ApiEnvelope<FollowDetailList>

    @POST("feed/follows")
    suspend fun follow(@Body body: FollowRequest): ApiEnvelope<JsonElement>

    /** DELETE 带 body 定位目标 */
    @HTTP(method = "DELETE", path = "feed/follows", hasBody = true)
    suspend fun unfollow(@Body body: FollowRequest): ApiEnvelope<JsonElement>

    /** 埋点批量上报（可选认证，匿名可用） */
    @POST("feed/events")
    suspend fun reportEvents(@Body body: FeedEventsRequest): ApiEnvelope<JsonElement>
}
