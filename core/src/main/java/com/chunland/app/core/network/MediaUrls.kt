package com.chunland.app.core.network

/**
 * API 基址持有者 —— 由 [com.chunland.app.core.CoreGraph] 构造时写入。
 * :core 是 library module，读不到 :app 的 BuildConfig（也不该感知构建配置），
 * 故基址从 :app 单向注入进来，下面两个顶层函数的调用点保持不变。
 */
object MediaConfig {
    @Volatile
    var apiBaseUrl: String = ""
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
fun serverOrigin(): String = MediaConfig.apiBaseUrl.removeSuffix("/").removeSuffix("/api/v1")
