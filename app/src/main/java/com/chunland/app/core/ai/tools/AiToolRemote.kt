package com.chunland.app.core.ai.tools

import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.apiCall
import com.chunland.app.core.ai.AiToolScope
import com.chunland.app.core.ai.domain.AgentCard
import com.chunland.app.core.ai.domain.AgentToolInput
import com.chunland.app.data.api.AiToolCallRequest
import com.chunland.app.data.api.AiToolCallScope
import com.chunland.app.data.api.AiToolCommitRequest

// MARK: - 服务端工具体的调用侧（R5，对齐 iOS AIToolRemote.swift）
//
// 工具体从端上搬到服务端后，端上只做三件事：把参数发过去、把文本原样喂回模型、
// 把服务端告知的 id 登记进会话 provenance。
//
// ⚠️ 服务端返回的 text **已经消毒并包好数据围栏**，端上绝不能再过一次
// `AiFence.sanitize` —— 那会把服务端加的标记一并中和掉，围栏就白做了。
// 管道据此把这类结果标成「已处理」直接透传。

/** 服务端工具体的返回。 */
data class AiToolRemoteResult(
    /** 已消毒 + 已围栏的文本，原样喂回模型。 */
    val text: String,
    /** 结果里出现的 id，按类别分组 —— 端上据此登记会话 provenance。 */
    val ids: Map<AiProvenanceKind, List<String>>,
    /** 结构化卡片（R3）—— **给用户看的那一份，不喂给模型**。 */
    val cards: List<AgentCard> = emptyList(),
)

/** wire DTO → domain。core 不依赖 app，所以映射发生在这里。 */
private fun toCard(d: com.chunland.app.data.api.AiToolCardDto) = AgentCard(
    kind = d.kind, code = d.code, name = d.name, price = d.price,
    originalPrice = d.originalPrice, inStock = d.inStock, thumbnail = d.thumbnail,
)

class AiToolRemote(private val graph: AppGraph) {

    /** 服务端解析的结果。 */
    sealed interface RemotePrepared {
        /**
         * 前置条件不满足（地址簿空、未达起送）。**不是错误** ——
         * 文本直接作为工具结果回给模型，让它换个做法。
         */
        data class Abort(val text: String) : RemotePrepared

        /** 解析完成：intent 给用户确认，通过后拿 token 去 commit。 */
        data class Ready(
            val token: String,
            val summary: String,
            val details: Map<String, String>,
        ) : RemotePrepared
    }

    /**
     * 执行期解析：解析好的快照留在服务端，端上只拿到票据与给用户看的意图。
     *
     * **「确认里看到的 = 实际执行的」由服务端持有快照来保证** ——
     * 端上在确认前后发什么都改不了将要执行的内容，比纯端上实现还严一点。
     */
    suspend fun prepare(
        name: String,
        args: AgentToolInput,
        identity: String,
        scope: AiToolScope,
    ): RemotePrepared {
        val resp = apiCall {
            graph.aiToolApi.prepare(
                AiToolCallRequest(
                    name = name,
                    identity = identity,
                    args = args.json,
                    scope = AiToolCallScope(scope.merchantId, scope.merchantName),
                ),
            )
        }
        if (resp.kind == "abort") {
            return RemotePrepared.Abort(resp.text ?: "无法执行这个操作。")
        }
        val token = resp.token
        val intent = resp.intent
        if (token == null || intent == null) error("确认信息不完整")
        return RemotePrepared.Ready(token, intent.summary, intent.details)
    }

    /** 用户确认后执行那份快照。 */
    suspend fun commit(token: String): AiToolRemoteResult {
        val resp = apiCall { graph.aiToolApi.commit(AiToolCommitRequest(token)) }
        val ids = buildMap {
            for ((raw, values) in resp.ids) {
                val kind = AiProvenanceKind.fromWire(raw) ?: continue
                put(kind, values)
            }
        }
        return AiToolRemoteResult(resp.text, ids, resp.cards.map(::toCard))
    }

    /**
     * 调用一个已搬到服务端的工具。
     *
     * 抛错交给管道统一处理成错误结果（会被消毒、不进围栏 —— 那是控制文案）。
     */
    suspend fun call(
        name: String,
        args: AgentToolInput,
        identity: String,
        scope: AiToolScope,
    ): AiToolRemoteResult {
        val resp = apiCall {
            graph.aiToolApi.call(
                AiToolCallRequest(
                    name = name,
                    identity = identity,
                    args = args.json,
                    scope = AiToolCallScope(scope.merchantId, scope.merchantName),
                ),
            )
        }
        val ids = buildMap {
            for ((raw, values) in resp.ids) {
                val kind = AiProvenanceKind.fromWire(raw) ?: continue
                put(kind, values)
            }
        }
        return AiToolRemoteResult(resp.text, ids, resp.cards.map(::toCard))
    }
}
