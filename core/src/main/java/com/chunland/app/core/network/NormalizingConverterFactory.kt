package com.chunland.app.core.network

import java.lang.reflect.Type
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit

/**
 * kotlinx 序列化转换器 + **响应键归一化**。
 *
 * wire 真相：请求侧服务端中间件把 body 键 snake→camel 归一化
 * （camelCase 透传），响应侧**没有对称处理** —— 手工构建的对象是 camelCase（auth 等
 * 四层 service），SQL 行直接 spread 的单层路由是 snake_case（products / categories）。
 * iOS 靠 convertFromSnakeCase 在解码前把两种都归一到 camelCase；这里做同一件事：
 * 响应解析成 JsonElement 后递归 snake→camel 再解码。转换正则与服务端请求侧完全一致
 * （`_[a-z]` 才转），"CN15" 这类数据键不受影响。请求编码不做转换（camelCase 直发）。
 */
class NormalizingConverterFactory(private val json: Json) : Converter.Factory() {

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *> {
        val serializer = json.serializersModule.serializer(type)
        return Converter<ResponseBody, Any?> { body ->
            val element = body.use { json.parseToJsonElement(it.string()) }
            json.decodeFromJsonElement(serializer, element.camelizeKeys())
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody> {
        @Suppress("UNCHECKED_CAST")
        val serializer = json.serializersModule.serializer(type) as KSerializer<Any?>
        return Converter<Any?, RequestBody> { value ->
            json.encodeToString(serializer, value).toRequestBody(MEDIA_TYPE)
        }
    }

    private companion object {
        val MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
    }
}

private val SNAKE_SEGMENT = Regex("_([a-z])")

internal fun String.snakeToCamel(): String =
    SNAKE_SEGMENT.replace(this) { it.groupValues[1].uppercase() }

internal fun JsonElement.camelizeKeys(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.associate { (k, v) -> k.snakeToCamel() to v.camelizeKeys() })
    is JsonArray -> JsonArray(map { it.camelizeKeys() })
    else -> this
}
