package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * AI 工具体的服务端落点（R5）。
 *
 * 工具体从端上搬到服务端后，端上只做三件事：把参数发过去、把文本原样喂回模型、
 * 把服务端告知的 id 登记进会话 provenance。
 *
 * **变更工具的 schema 不在这里** —— 钉死在端上：`kind: MUTATION` 决定弹不弹 HITL
 * 确认框，那道闸门不能由下发内容决定。只读工具的 schema 可以下发（[schema]）。
 */
interface AiToolApi {

    /**
     * 线上下发的工具 schema（只出非 pinned 的只读工具）。
     *
     * 端上收到的一律当只读 —— 见 app 侧 `AgentRemoteToolSpec`：它结构上就没有
     * kind 字段，表达不出「这是个变更工具」。
     */
    @GET("ai/tools/schema")
    suspend fun schema(@Query("identity") identity: String): ApiEnvelope<AiToolSchemaResponse>

    @POST("ai/tools/call")
    suspend fun call(@Body body: AiToolCallRequest): ApiEnvelope<AiToolCallResponse>

    /**
     * 变更工具的执行期解析：解析好的快照留在服务端，端上只拿票据与给用户看的意图。
     *
     * **「确认里看到的 = 实际执行的」由服务端持有快照来保证** ——
     * 端上在确认前后发什么都改不了将要执行的内容。
     */
    @POST("ai/tools/prepare")
    suspend fun prepare(@Body body: AiToolCallRequest): ApiEnvelope<AiToolPrepareResponse>

    /** 用户确认后执行那份快照。票据一次性 —— 重试或连点得到「票据已失效」而不是执行两次。 */
    @POST("ai/tools/commit")
    suspend fun commit(@Body body: AiToolCommitRequest): ApiEnvelope<AiToolCallResponse>
}

@Serializable
data class AiToolCommitRequest(val token: String)

@Serializable
data class AiToolSchemaResponse(
    /** 改了任何 wire 工具的 schema 服务端就递增；端上据此判缓存过期 */
    val version: Int = 0,
    val tools: List<AiWireToolDto> = emptyList(),
)

@Serializable
data class AiWireToolDto(
    val name: String,
    val description: String,
    val identities: List<String> = emptyList(),
    val params: List<AiWireParamDto> = emptyList(),
)

/**
 * ⚠️ **参数是数组不是字典。**
 *
 * 参数名本身是 snake_case（`price_min` 这类），当成 JSON 的 key 会被端上的
 * 键归一化（iOS 的 convertFromSnakeCase / 这里的 NormalizingConverterFactory）
 * 一并转成 `priceMin` —— 发给模型的参数名与钉死工具的口径就对不上了，且不报错。
 * 放进 `name` 字段（是值不是键）就没这问题。
 */
@Serializable
data class AiWireParamDto(
    val name: String,
    val type: String,
    val description: String = "",
    val required: Boolean = false,
    val enumValues: List<String>? = null,
    val itemType: String? = null,
)

@Serializable
data class AiToolPrepareResponse(
    /** "abort" = 前置条件不满足（不是错误，文本回给模型让它换做法）；"ready" = 可确认 */
    val kind: String,
    val text: String? = null,
    val token: String? = null,
    val intent: AiToolIntent? = null,
)

@Serializable
data class AiToolIntent(
    val summary: String,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class AiToolCallRequest(
    val name: String,
    /** 端上当前活跃身份。服务端**不信它** —— 会与 JWT 里的角色交叉校验 */
    val identity: String,
    /** 模型给的参数（已经过端上的修复与 preflight） */
    val args: JsonObject,
    val scope: AiToolCallScope,
)

@Serializable
data class AiToolCallScope(
    val merchantId: Int? = null,
    val merchantName: String? = null,
)

@Serializable
data class AiToolCallResponse(
    /** 已消毒且已围栏 —— 端上原样喂回模型，**绝不再过一遍消毒** */
    val text: String,
    /**
     * 结果里出现的 id，按类别分组（键是 AiProvenanceKind 的小驼峰名）。
     *
     * 用 Map<String, List<String>> 收而不是强类型枚举 —— 服务端将来加新类别时，
     * 老客户端应当**静默忽略**而不是整条解码失败。
     */
    val ids: Map<String, List<String>> = emptyMap(),
    /**
     * 结构化卡片（R3）—— **给用户看的那一份，不喂给模型**。
     * 老服务端不返这个字段，缺省空表。
     */
    val cards: List<AiToolCardDto> = emptyList(),
)

/**
 * 结构化卡片的 wire 形状（R3）。
 *
 * 放在 core 而不是复用 app 侧的 domain 类型 —— core 不依赖 app。
 * app 侧在 `AiToolRemote` 里映射成 domain 的 AgentCard。
 *
 * 带 kind 而不是直接 ProductCard：将来加订单卡/店铺卡时，
 * **老客户端能静默跳过不认识的 kind**，而不是整条解码失败。
 */
@Serializable
data class AiToolCardDto(
    val kind: String,
    val code: String? = null,
    val name: String? = null,
    val price: Double? = null,
    val originalPrice: Double? = null,
    val inStock: Boolean? = null,
    val thumbnail: String? = null,
)
