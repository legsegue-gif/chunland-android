package com.chunland.app.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * 全局 JSON 配置（单一真相源，Retrofit 转换器与单测共用）。
 *
 * wire 契约：
 * - **请求**：camelCase 直发（服务端中间件对 camelCase 透传，路由消费的就是 camelCase）；
 * - **响应**：混合格式 —— 手工对象 camelCase、SQL 裸行 snake_case。由
 *   [NormalizingConverterFactory] 在解码前统一归一到 camelCase（对齐 iOS convertFromSnakeCase）。
 *
 * DTO 一律写 camelCase 属性名。禁止加 JsonNamingStrategy、禁止手写 @SerialName 转 case ——
 * 归一化只在转换器一处做。
 */
@OptIn(ExperimentalSerializationApi::class)
val ChunlandJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}
