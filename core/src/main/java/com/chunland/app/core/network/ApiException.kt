package com.chunland.app.core.network

/**
 * 统一网络错误体系（对齐 iOS 的 APIError enum）。
 * Forbidden / RateLimited / Validation 是服务端扩展点（权限/限流/schema 校验）开启后自动触发的预留分支。
 */
sealed class ApiException(message: String) : Exception(message) {

    class Network(cause: Throwable) : ApiException("网络异常，请稍后重试") {
        init { initCause(cause) }
    }

    /** 401 只留给 token 失效（TokenAuthenticator 刷新失败后才会浮出到这里） */
    class Unauthorized : ApiException("请先登录")

    class Forbidden : ApiException("无权限")

    class RateLimited(val retryAfterSeconds: Long?) : ApiException(
        retryAfterSeconds?.let { "请求过于频繁，请 ${it}s 后重试" } ?: "请求过于频繁，请稍后重试"
    )

    class Validation(val fields: List<FieldError>) : ApiException(
        fields.firstOrNull()?.let { "${it.field}: ${it.message}" } ?: "参数校验失败"
    )

    /** 业务失败：信封 code != 0 或非 2xx，message 用服务端下发的文案直接给用户看 */
    class Server(val httpStatus: Int, message: String) : ApiException(message)

    class Decoding(cause: Throwable) : ApiException("数据解析失败") {
        init { initCause(cause) }
    }
}

data class FieldError(val field: String, val message: String)

/** 给 UI 层兜底转文案（业务 mutator 返回 String? 约定的原料） */
val Throwable.userMessage: String
    get() = when (this) {
        is ApiException -> message ?: "请求失败"
        else -> "网络异常，请稍后重试"
    }
