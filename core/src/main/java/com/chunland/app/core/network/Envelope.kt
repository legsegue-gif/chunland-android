package com.chunland.app.core.network

import kotlinx.serialization.Serializable

/** 服务端统一响应信封：`{ "code": 0, "message": "ok", "data": {...} }`，code == 0 为成功。 */
@Serializable
data class ApiEnvelope<T>(
    val code: Int = -1,
    val message: String = "",
    val data: T? = null,
)
