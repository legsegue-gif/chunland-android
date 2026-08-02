package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.AgentDashboard
import com.chunland.app.data.model.AgentProfile
import com.chunland.app.data.model.PurchaseList
import com.chunland.app.data.model.SettlementSummary
import com.chunland.app.data.model.UpdateAgentProfileRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH

interface AgentProfileApi {

    /** 代购人资料：接单开关/服务区县/简介 + 只读统计（评分/累计单） */
    @GET("agent-profile")
    suspend fun profile(): ApiEnvelope<AgentProfile>

    /** 部分更新：不发的字段不改 */
    @PATCH("agent-profile")
    suspend fun updateProfile(@Body body: UpdateAgentProfileRequest): ApiEnvelope<AgentProfile>

    /** 工作台聚合：待办计数 + 收入汇总（只读；需 agent 角色） */
    @GET("agent-profile/dashboard")
    suspend fun dashboard(): ApiEnvelope<AgentDashboard>

    /** 合并采购清单：待采购/采购中订单按商家分组、同商品跨单聚合（只读） */
    @GET("agent-profile/purchase-list")
    suspend fun purchaseList(): ApiEnvelope<PurchaseList>

    /** 代购人待结算账 + 余额聚合（只读；需 agent 角色） */
    @GET("agent-profile/settlements")
    suspend fun settlements(): ApiEnvelope<SettlementSummary>
}
