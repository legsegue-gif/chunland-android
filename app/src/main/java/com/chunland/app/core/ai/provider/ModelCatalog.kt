package com.chunland.app.core.ai.provider

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 从端点拉可用模型列表（对齐 iOS ModelCatalog.swift）。
 *
 * 让用户在配置来源时能「选」而不是「背」—— 手打模型名打错了要等到发第一条消息才报错，
 * 而且错在哪并不明显。
 *
 * ⚠️ 这是**锦上添花的能力，不是必经步骤**：OpenAI 兼容只规定了 /chat/completions，
 * `/models` 有的端点不实现、有的要求另一套鉴权。所以拉取失败绝不能挡住保存 ——
 * 手填那条路必须一直留着。
 *
 * ⚠️ 与 [OpenAiCompatibleProvider] 同一条红线：**裸 OkHttpClient，绝不复用主 client** ——
 * 那条链挂着认证拦截器，会把本项目的 token 泄给第三方服务。
 */
object ModelCatalog {

    /** 拉取失败。[message] 就是给用户看的文案（三种真实响应都实测过：200 / 401 / 404） */
    class FetchException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 拉取模型 id 列表。[baseUrl] 是含 /v1 的完整地址（与 provider 用的是同一个）。 */
    suspend fun fetch(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = baseUrl.trim().trimEnd('/')
        val request = runCatching {
            Request.Builder()
                .url("$trimmed/models")
                .get()
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
        }.getOrElse { throw FetchException("接口地址看起来不对，请检查后重试") }

        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { throw FetchException(LlmError.fromThrowable(it).userMessage) }

        val body = response.use { resp ->
            when (resp.code) {
                200 -> resp.body?.string().orEmpty()
                401, 403 -> throw FetchException("API Key 无效或没有权限")
                404, 405 -> throw FetchException("这个端点不支持列出模型，请手动填写模型名")
                else -> throw FetchException("获取失败（HTTP ${resp.code}），请手动填写模型名")
            }
        }

        parseModelIds(body)
    }

    /**
     * 解析响应体里的模型 id。
     *
     * 标准形状是 `{"object":"list","data":[{"id":...}]}`，但也见过直接返回数组的实现 ——
     * 两种都收，不为一个不规范的端点把功能判死。
     */
    internal fun parseModelIds(body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val rows: List<JsonObject> = when (root) {
            is JsonObject -> (root["data"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?: throw FetchException(BAD_RESPONSE)
            is JsonArray -> root.filterIsInstance<JsonObject>()
            else -> throw FetchException(BAD_RESPONSE)
        }

        val ids = rows.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.filter { it.isNotBlank() }
        if (ids.isEmpty()) throw FetchException(BAD_RESPONSE)
        // 去重后按名字排 —— 端点返回的顺序通常是创建时间，对找模型没帮助
        return ids.distinct().sorted()
    }

    private const val BAD_RESPONSE = "返回的内容看不懂，请手动填写模型名"
}
