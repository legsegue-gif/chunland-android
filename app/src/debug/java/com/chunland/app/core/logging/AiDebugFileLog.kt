package com.chunland.app.core.logging

import android.content.Context
import android.util.Log
import com.chunland.app.core.ai.domain.AgentContentPart
import com.chunland.app.core.ai.domain.AgentMessage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * AI 对话调试日志 —— **只有 debug 构建有这份实现**（对齐 iOS `AIDebugFileLog.swift` 的 `#if DEBUG`）。
 *
 * release 源集里是同名同签名的空壳（`src/release/…/AiDebugFileLog.kt`），所以正式包里
 * 这段代码与它的字符串**物理不存在**，不产生任何文件 IO。Kotlin 没有预处理器，双源集是
 * 拿到「编译期剔除」的唯一手段 —— 只用 `if (BuildConfig.DEBUG)` 守卫的话，类体与常量仍会
 * 打进正式 APK（本项目 release 也没开 minify，R8 不会替你剔）。
 * ⚠️ 改这里的公开签名必须同步改 release 空壳，否则 release 构建直接失败（这道失败是有意的保护）。
 *
 * 落盘到 `cacheDir/shared/ai-debug.log`，格式 `[ts] LEVEL msg {compact json}`，
 * 与 iOS 逐字一致，两端日志可直接对比。放 `shared/` 是因为 FileProvider 只暴露这个子目录
 * （见 `res/xml/file_paths.xml`），「开发者」页要把它交给系统分享面板。
 *
 * 因为整段只在本机 debug 存在、不进正式包，这里记录**完整对话内容**
 * （不像服务端日志需要脱 body 避免 CWE-532）。
 */
object AiDebugFileLog {

    private const val TAG = "AiDebugLog"
    private const val FILE_NAME = "ai-debug.log"

    @Volatile
    private var logFile: File? = null

    /** 由 Application 启动时调用一次。建空文件是幂等的。 */
    fun install(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            logFile = File(dir, FILE_NAME).apply { if (!exists()) createNewFile() }
        }.onFailure { Log.w(TAG, "日志文件初始化失败，本次运行不落盘", it) }
    }

    /** 供「开发者」页分享用；无内容时返回 null。 */
    fun file(): File? = logFile?.takeIf { it.exists() && it.length() > 0 }

    /** 入口是否显示 —— 有内容才显示，避免分享一个空文件（对齐 iOS）。 */
    fun hasContent(): Boolean = file() != null

    /** 清空日志内容（截断为 0 字节，文件本身保留），对齐 iOS `AIDebugFileLog.clear()`。 */
    fun clear() {
        runCatching { logFile?.writeText("") }
    }

    /** 发起请求前记录：model / 工具列表 / 完整历史（按 domain 形状序列化）。 */
    fun request(model: String, toolNames: List<String>, messages: List<AgentMessage>) {
        emit(
            "ai.request",
            buildMap {
                put("model", JsonPrimitive(model))
                put("tools", JsonArray(toolNames.map { JsonPrimitive(it) }))
                put("messages", JsonArray(messages.map(::describe)))
            },
        )
    }

    /** 一轮结束时记录：结束原因 + 本轮产出。 */
    fun response(
        outcome: String,
        text: String?,
        toolNames: List<String> = emptyList(),
        detail: String? = null,
    ) {
        emit(
            "ai.response",
            buildMap {
                put("outcome", JsonPrimitive(outcome))
                if (!text.isNullOrEmpty()) put("text", JsonPrimitive(text))
                if (toolNames.isNotEmpty()) put("toolCalls", JsonArray(toolNames.map { JsonPrimitive(it) }))
                if (detail != null) put("detail", JsonPrimitive(detail))
            },
        )
    }

    // 打 logcat + 追加落盘。落盘失败静默吞掉 —— 日志不该成为业务故障源。
    private fun emit(msg: String, extra: Map<String, JsonElement>) {
        val line = "[${ts()}] INFO  $msg ${JsonObject(extra)}"
        Log.i(TAG, line)
        runCatching { logFile?.appendText(line + "\n") }
    }

    private fun ts(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** domain 消息 → 可读结构。**不落图片字节，只记引用**（日志不该变成图床）。 */
    private fun describe(message: AgentMessage): JsonElement {
        val parts = message.parts.map { part ->
            when (part) {
                is AgentContentPart.Text -> JsonObject(
                    mapOf("kind" to JsonPrimitive("text"), "text" to JsonPrimitive(part.text)),
                )

                is AgentContentPart.ToolUse -> JsonObject(
                    mapOf(
                        "kind" to JsonPrimitive("tool_use"),
                        "id" to JsonPrimitive(part.id),
                        "name" to JsonPrimitive(part.name),
                        "input" to JsonPrimitive(part.input.jsonString()),
                    ),
                )

                is AgentContentPart.Cards -> JsonObject(
                    mapOf(
                        "kind" to JsonPrimitive("cards"),
                        "count" to JsonPrimitive(part.cards.size),
                    ),
                )

                is AgentContentPart.ToolResult -> JsonObject(
                    buildMap {
                        put("kind", JsonPrimitive("tool_result"))
                        put("id", JsonPrimitive(part.id))
                        put("name", JsonPrimitive(part.name))
                        put("isError", JsonPrimitive(part.isError))
                        put("text", JsonPrimitive(part.text))
                        part.offloadRef?.let { put("offloadRef", JsonPrimitive(it)) }
                    },
                )

                is AgentContentPart.Image -> JsonObject(
                    mapOf(
                        "kind" to JsonPrimitive("image"),
                        "sha256" to JsonPrimitive(part.media.sha256),
                        "bytes" to JsonPrimitive(part.media.bytes),
                    ),
                )
            }
        }
        return JsonObject(
            buildMap {
                put("role", JsonPrimitive(message.role.name.lowercase()))
                put("parts", JsonArray(parts))
                if (message.isInterrupted) put("interrupted", JsonPrimitive(true))
                message.reasoning?.let { put("reasoning", JsonPrimitive(it)) }
            },
        )
    }
}
