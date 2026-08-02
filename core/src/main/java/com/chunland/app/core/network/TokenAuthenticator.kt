package com.chunland.app.core.network

import com.chunland.app.core.auth.AuthManager
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 401 → 刷新 token → 原请求重试一次（OkHttp 在响应层自动重放，对齐 iOS APIClient 语义）。
 *
 * - 原请求没带 Authorization（登录/发码/刷新自身）→ 不刷新，401 原样浮出；
 * - 刷过一次仍 401 → 放弃（AuthManager 已在刷新失败路径里强制登出）。
 */
class TokenAuthenticator(private val authManager: () -> AuthManager) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val sent = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?: return null
        if (priorCount(response) >= 1) return null

        val fresh = runBlocking { authManager().refreshedAccessToken(previous = sent) }
            ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $fresh")
            .build()
    }

    private fun priorCount(response: Response): Int {
        var count = 0
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
