package com.chunland.app.core.network

/**
 * API 基址取用点 —— 由 [com.chunland.app.core.CoreGraph] 构造时注入。
 * :core 是 library module，读不到 :app 的 BuildConfig（也不该感知构建配置），
 * 故基址从 :app 单向注入进来，下面两个顶层函数的调用点保持不变。
 *
 * 存 provider 而非快照：Debug 下用户可切服务器地址，图片与合规页地址必须跟着变。
 * 存字符串就得多一个写入方去同步，迟早漏。
 */
object MediaConfig {
    @Volatile
    var baseUrlProvider: () -> String = { "" }
}

/**
 * 图片地址归一：服务端 resolveImageUrl 在 PUBLIC_BASE_URL 未配置（dev 常见）时会下发
 * 相对路径（/api/v1/media/...），此时拼上当前 API 的 origin；绝对 URL 原样返回。
 */
fun absoluteMediaUrl(url: String?): String? = when {
    url.isNullOrBlank() -> null
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
    else -> serverOrigin() + "/" + url.trimStart('/')
}

/** 当前 API 的站点 origin。静态合规页（/terms /privacy /support）随服务器走，不硬编码域名。 */
fun serverOrigin(): String =
    MediaConfig.baseUrlProvider().removeSuffix("/").removeSuffix("/api/v1")
