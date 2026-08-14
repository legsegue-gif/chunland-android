package com.chunland.app.core.ai.provider

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * LLM 错误分类（对齐 iOS LLMError.swift）。
 *
 * 整套重试与降级机制的地基。核心是两个**正交**的判定：
 *
 * - [isRetryable]：同一个模型重试有意义吗？（网络抖动、上游 5xx）
 * - [isFallbackable]：这个 provider 根本服务不了，该换一个吗？（限流、密钥无效、拒绝）
 *
 * 它们不是「两类错误」而是「两个独立提问」：瞬时错误先在当前模型重试，
 * 重试耗尽后同样要进降级路径。把两者混成一个枚举会让「重试几次后换模型」
 * 这个最常见的策略无法表达。
 */
sealed class LlmError(message: String) : Exception(message) {

    /** 密钥无效 / 未授权（401、403） */
    data class InvalidApiKey(val detail: String) : LlmError("invalid_api_key: $detail")

    /** 网络层失败（连不上、超时、连接中断） */
    data class NetworkError(val detail: String) : LlmError("network: $detail")

    /** 上游明确拒绝了这个请求（4xx，非鉴权类） */
    data class ProviderError(val detail: String) : LlmError("provider: $detail")

    /** 上游临时不可用（500/502/503/504/529）。重试有意义 */
    data class TransientError(val detail: String) : LlmError("transient: $detail")

    /** 被限流（429） */
    data class RateLimited(val retryAfterSeconds: Int? = null) : LlmError("rate_limited")

    /** 响应解析失败（返回了非预期的结构） */
    data class DecodingError(val detail: String) : LlmError("decoding: $detail")

    /** 用户主动取消 */
    data object Cancelled : LlmError("cancelled")

    /** 未配置可用的模型 */
    data object NotConfigured : LlmError("not_configured")

    /** 系统 AI 暂不可用（本机服务未就绪 / 已停用） */
    data class SystemProviderUnavailable(val reason: String) : LlmError("system_unavailable: $reason")

    data class Unknown(val detail: String) : LlmError("unknown: $detail")

    // MARK: - 两个正交判定

    /** 同一模型重试有意义 —— 走递增倒计时重试 */
    val isRetryable: Boolean
        get() = when (this) {
            is NetworkError, is TransientError -> true
            else -> false
        }

    /**
     * 这个 provider 服务不了 —— 立刻换下一个模型，不在当前模型重试。
     *
     * 注意 [TransientError] / [NetworkError] **不在此列**，但它们在
     * 自动重试耗尽后同样会进入降级 —— 那是调用方的策略，不是错误本身的属性。
     */
    val isFallbackable: Boolean
        get() = when (this) {
            is RateLimited, is InvalidApiKey, is ProviderError, is SystemProviderUnavailable -> true
            else -> false
        }

    val isCancellation: Boolean get() = this is Cancelled

    /** 降级时记录的原因，最终会展示给用户（「模型 A 限流 → 已切到模型 B」） */
    val fallbackReason: String
        get() = when (this) {
            is RateLimited -> "请求过于频繁"
            is InvalidApiKey -> "密钥无效"
            is ProviderError -> "服务拒绝：${detail.take(40)}"
            is TransientError -> "服务暂时不可用"
            is NetworkError -> "网络异常"
            is SystemProviderUnavailable -> reason
            else -> "调用失败"
        }

    /**
     * 用户可见文案。
     *
     * 错误原样透传对用户是天书（「HTTP 429」）。这里统一成人话，
     * 原始细节由调用方记日志，排查不受影响。
     */
    val userMessage: String
        get() = when (this) {
            is InvalidApiKey -> "AI 服务鉴权失败，请到「AI 配置」检查来源或 API Key。"
            is NetworkError -> "网络连接不可用，请检查网络后重试。"
            is ProviderError -> if (detail.isBlank()) "AI 服务拒绝了这次请求。" else "AI 服务拒绝了这次请求：$detail"
            is TransientError -> "AI 服务暂时不可用，请稍后再试。"
            is RateLimited -> retryAfterSeconds?.let { "请求过于频繁，请 $it 秒后再试。" }
                ?: "AI 服务当前请求较多，请稍等片刻再试。"
            is DecodingError -> "AI 返回了无法识别的内容，请重试。"
            Cancelled -> "已取消。"
            NotConfigured -> "尚未配置可用的 AI 模型，请到「AI 配置」选择。"
            is SystemProviderUnavailable -> reason
            is Unknown -> if (detail.isBlank()) "AI 请求失败，请重试。" else "AI 请求失败：$detail"
        }

    companion object {
        /**
         * HTTP 状态码 → 错误分类。
         *
         * 分类的依据是**该怎么处置**，不是状态码本身：
         * 401/403 要用户去改配置，429 要等或换号，5xx 等一会儿多半自己好，
         * 其余 4xx 是请求本身有问题、重试没用。
         */
        fun fromHttpStatus(status: Int, body: String = ""): LlmError {
            val detail = body.take(200)
            return when (status) {
                401, 403 -> InvalidApiKey(detail)
                429 -> RateLimited()
                500, 502, 503, 504, 529 -> TransientError("HTTP $status")
                in 400..499 -> ProviderError(detail.ifBlank { "HTTP $status" })
                else -> Unknown("HTTP $status")
            }
        }

        /** 传输层异常 → 错误分类 */
        fun fromThrowable(t: Throwable): LlmError = when (t) {
            is LlmError -> t
            is kotlinx.coroutines.CancellationException -> Cancelled
            is UnknownHostException, is SocketTimeoutException, is SSLException ->
                NetworkError(t.message ?: t.javaClass.simpleName)
            is IOException -> NetworkError(t.message ?: "IO 异常")
            else -> Unknown(t.message ?: t.javaClass.simpleName)
        }
    }
}
