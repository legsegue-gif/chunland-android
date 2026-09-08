package com.chunland.app.core.network

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 把哨兵基址改写成当前真实基址（[ServerConfig.baseUrl]）。
 *
 * 为什么需要：Retrofit 的 `baseUrl` 在实例构造时就固化成 `HttpUrl`，没有 iOS `APIClient`
 * 那种「每次请求现取基址再拼」的入口（见 APIClient 的 `state.withLock { $0.baseURL }`）。
 * 要拿到同样的「改完下一个请求即生效、不必重启」，只能在出站时整体重挂 ——
 * [com.chunland.app.core.CoreGraph] 用固定哨兵建 Retrofit，这里换成真实地址。
 *
 * 拼接之所以无歧义：全部接口路径都是**无前导斜杠的相对路径**（约定见 `AuthApi` 顶部注释），
 * 所以哨兵下解析出的 `encodedPath` 就是干净的路径段，直接接到新基址后面即可。
 *
 * ⚠️ 必须挂在 `HttpLoggingInterceptor` **之前**，否则日志里打的是哨兵地址，排障时指错方向。
 */
class BaseUrlInterceptor(private val provider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        // provider 给的值已由 ServerConfig 校验过（兜底是编译默认），正常不会解析失败。
        // 万一失败就原样放行：让它打到哨兵域名快速失败，好过静默改写到别处去。
        val url = rewrite(provider(), req.url) ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().url(url).build())
    }

    companion object {
        /**
         * 把 [requestUrl] 的路径与查询重挂到 [base] 上；[base] 不可解析时返回 null。
         *
         * 拆成纯函数是为了能直接单测 —— 这段拼接一旦悄悄写错（丢掉 `/api/v1` 前缀、
         * 吞掉查询串、把已编码字符二次编码），表现是「请求 404 或参数丢失」，
         * 从 UI 上根本看不出是这里的问题。
         */
        fun rewrite(base: String, requestUrl: HttpUrl): HttpUrl? {
            val b = (base.trimEnd('/') + "/").toHttpUrlOrNull() ?: return null
            return b.newBuilder()
                .addEncodedPathSegments(requestUrl.encodedPath.trimStart('/'))
                .encodedQuery(requestUrl.encodedQuery)
                .build()
        }
    }
}

/**
 * 给每个请求附加 Bearer token。
 * /auth/refresh 例外不附加 —— 它自己就是刷新通道，带上过期 token 会让
 * TokenAuthenticator 在 401 时误判「有 token 该刷新」造成递归。
 */
class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val isRefresh = req.url.encodedPath.endsWith("/auth/refresh")
        val token = tokenProvider()
        val out = if (token != null && !isRefresh) {
            req.newBuilder().header("Authorization", "Bearer $token").build()
        } else req
        return chain.proceed(out)
    }
}

/**
 * 基础遥测 header（对齐 iOS ClientInfo）：`app=0.1.0; build=1; os=Android 16; model=Pixel 8`。
 * 服务端 requestAudit 解析后落 api_request_logs。只含版本/系统/机型，无隐私数据。
 */
class ClientInfoInterceptor(context: Context) : Interceptor {
    private val headerValue: String = run {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        listOf(
            "app=${pkg.versionName}",
            "build=${PackageInfoCompat.getLongVersionCode(pkg)}",
            "os=Android ${Build.VERSION.RELEASE}",
            "model=${Build.MODEL}",
        ).joinToString("; ")
    }

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("X-Client-Info", headerValue).build())
}
