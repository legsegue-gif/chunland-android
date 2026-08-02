package com.chunland.app.core.network

import android.util.Log
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException

/**
 * 所有 API 调用的统一出口：拆信封 + 错误归一化。
 *
 * 服务端非 2xx 也带信封（{code, message}），必须解出 message 给用户看，
 * 不能让 HttpException 的 "HTTP 400" 直接透出（对齐 iOS：解码信封优先于状态码）。
 */
suspend fun <T> apiCall(block: suspend () -> ApiEnvelope<T>): T {
    val envelope = try {
        block()
    } catch (e: HttpException) {
        throw e.toApiException()
    } catch (e: SerializationException) {
        // 对齐 iOS AppLogger.network.error("decoding_failed")：解码失败必须在 Logcat 可见，
        // 否则只剩 snackbar 文案没法定位
        Log.w(TAG, "decoding_failed", e)
        throw ApiException.Decoding(e)
    } catch (e: IOException) {
        Log.w(TAG, "network_error: ${e.message}")
        throw ApiException.Network(e)
    }
    if (envelope.code != 0) throw ApiException.Server(200, envelope.message)
    return envelope.data ?: throw ApiException.Server(200, "空响应")
}

/** data 为 null 的接口（服务端 data:null）用这个变体。 */
suspend fun apiCallUnit(block: suspend () -> ApiEnvelope<JsonElement>) {
    val envelope = try {
        block()
    } catch (e: HttpException) {
        throw e.toApiException()
    } catch (e: SerializationException) {
        Log.w(TAG, "decoding_failed", e)
        throw ApiException.Decoding(e)
    } catch (e: IOException) {
        Log.w(TAG, "network_error: ${e.message}")
        throw ApiException.Network(e)
    }
    if (envelope.code != 0) throw ApiException.Server(200, envelope.message)
}

private const val TAG = "ChunlandApi"

private fun HttpException.toApiException(): ApiException {
    val status = code()
    if (status == 401) return ApiException.Unauthorized()

    // 尽力从错误体里解信封 message；解不出再回退通用文案
    val message = runCatching {
        val body = response()?.errorBody()?.string().orEmpty()
        ChunlandJson.decodeFromString<ApiEnvelope<JsonElement>>(body).message
    }.getOrNull().takeUnless { it.isNullOrBlank() }

    return when (status) {
        403 -> ApiException.Forbidden()
        429 -> ApiException.RateLimited(
            response()?.headers()?.get("Retry-After")?.toLongOrNull()
        )
        else -> ApiException.Server(status, message ?: "请求失败")
    }
}
