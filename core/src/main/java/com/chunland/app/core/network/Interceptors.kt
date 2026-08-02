package com.chunland.app.core.network

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.Interceptor
import okhttp3.Response

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
