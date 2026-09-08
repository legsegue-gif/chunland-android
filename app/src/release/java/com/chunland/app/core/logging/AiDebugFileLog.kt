package com.chunland.app.core.logging

import android.content.Context
import com.chunland.app.core.ai.domain.AgentMessage
import java.io.File

/**
 * AI 对话调试日志的 **release 空壳** —— 对齐 iOS `AIDebugFileLog.swift` 的 `#if DEBUG` 语义：
 * 正式包里不存在任何实现代码、不落一行日志、不做一次文件 IO。
 *
 * 真实实现在 `src/debug/…/AiDebugFileLog.kt`。Kotlin 没有预处理器，双源集是拿到
 * 「编译期物理剔除」的唯一手段：只用 `if (BuildConfig.DEBUG)` 守卫的话，类体与它引用的
 * 字符串（完整对话内容的序列化逻辑、字段名）仍会打进正式 APK。
 *
 * ⚠️ 这里的签名必须与 debug 版**逐字一致** —— 调用方在 `src/main`，两个变体都要能编过。
 * 加方法先加这边，否则 release 构建会以 unresolved reference 失败（这道失败是有意的保护）。
 */
object AiDebugFileLog {

    fun install(context: Context) = Unit

    fun file(): File? = null

    fun hasContent(): Boolean = false

    fun clear() = Unit

    fun request(model: String, toolNames: List<String>, messages: List<AgentMessage>) = Unit

    fun response(
        outcome: String,
        text: String?,
        toolNames: List<String> = emptyList(),
        detail: String? = null,
    ) = Unit
}
