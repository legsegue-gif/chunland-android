package com.chunland.app.core.ai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OpenAI 兼容 chat/completions 的 wire 模型（直连用户自配 endpoint）。
 * ⚠️ 这不是 chunland 信封：没有 NormalizingConverterFactory，字段名按 OpenAI
 * 原样 @SerialName 手写（对齐 iOS AIOrchestrator 的 convertToSnakeCase 效果）。
 */
@Serializable
data class ChatWireMessage(
    val role: String,           // system | user | assistant | tool
    val content: String? = null,
    /** role == "tool"：这条结果回应的调用 id / 工具名 */
    @SerialName("tool_call_id") val toolCallId: String? = null,
    /** role == "assistant" 且请求了工具时非空 —— 历史回发必须原样携带 */
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    val name: String? = null,
)

/** 组装完成的 tool_call（对齐 iOS ToolCall；请求历史与流式收束共用） */
@Serializable
data class WireToolCall(
    val id: String,
    val type: String = "function",
    val function: WireToolCallFunction,
)

@Serializable
data class WireToolCallFunction(
    val name: String,
    val arguments: String,      // JSON string
)

@Serializable
data class ChatWireRequest(
    val model: String,
    val messages: List<ChatWireMessage>,
    val stream: Boolean = true,
    /** 发给模型的工具集（null = 纯聊天，不发字段；空数组会被部分 endpoint 拒收） */
    val tools: List<AiToolWire>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
)

@Serializable
data class StreamDelta(
    val content: String? = null,
    // 思考过程两种字段名（DeepSeek 标准 reasoning_content / 适配器变体 reasoning），对齐 iOS
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    // 兼容两类实现（对齐 iOS StreamToolCallDelta）：标准 OpenAI 按字符切片、按 index 累积；
    // 部分兼容端一次性完整返回（同一套 buffer 逻辑吃得下，无副作用）
    @SerialName("tool_calls") val toolCalls: List<StreamToolCallDelta>? = null,
) {
    val anyReasoning: String? get() = reasoningContent ?: reasoning
}

@Serializable
data class StreamToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionDelta? = null,
) {
    @Serializable
    data class FunctionDelta(
        val name: String? = null,
        val arguments: String? = null,
    )
}

@Serializable
data class StreamChoice(
    val delta: StreamDelta = StreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class StreamChunk(
    val choices: List<StreamChoice>? = null,
)

/** SSE event: error 帧的 payload（LM Studio / Ollama 等实现的常见形态），对齐 iOS SSEErrorPayload */
@Serializable
data class SseErrorPayload(
    val error: Detail? = null,
    val message: String? = null,
) {
    @Serializable
    data class Detail(val message: String? = null)
}

/**
 * 独立 Json 实例：外部 endpoint 的 wire 与 chunland 信封无关，刻意不共用 ChunlandJson。
 * encodeDefaults=true 是必须的：`stream:true`、`type:"function"`、`type:"object"` 这些
 * 常量默认值必须落 wire（默认 false 时 stream 字段被吞，真实 endpoint 会退化成非流式响应）。
 * explicitNulls=false 仍会把 null 字段（content/tools/tool_choice…）从 wire 里剥掉。
 */
@OptIn(ExperimentalSerializationApi::class)
val AiWireJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/** 一个 data: 载荷解析出的事件（行状态机在调用方：event: error 的跟踪、[DONE] 判断） */
sealed interface SseEvent {
    /** 增量：content / reasoning / toolCalls 至少一个非空，或带 finishReason */
    data class Delta(
        val content: String?,
        val reasoning: String?,
        val toolCalls: List<StreamToolCallDelta>?,
        val finishReason: String?,
    ) : SseEvent

    data class Error(val message: String) : SseEvent

    /** 无法解析或无 choices 的 chunk —— 跳过（对齐 iOS 的 continue） */
    data object Skip : SseEvent
}

/** 解析一行 `data: ` 后的 JSON 载荷。isErrorEvent = 前一行是 `event: error`。纯函数，单测锁契约。 */
fun parseSsePayload(payload: String, isErrorEvent: Boolean): SseEvent {
    if (isErrorEvent) {
        val msg = runCatching { AiWireJson.decodeFromString<SseErrorPayload>(payload) }
            .getOrNull()?.let { it.error?.message ?: it.message } ?: payload
        return SseEvent.Error(msg)
    }
    val chunk = runCatching { AiWireJson.decodeFromString<StreamChunk>(payload) }.getOrNull()
        ?: return SseEvent.Skip
    val choice = chunk.choices?.firstOrNull() ?: return SseEvent.Skip
    val content = choice.delta.content
    val reasoning = choice.delta.anyReasoning
    val toolCalls = choice.delta.toolCalls
    if (content.isNullOrEmpty() && reasoning.isNullOrEmpty() &&
        toolCalls.isNullOrEmpty() && choice.finishReason == null
    ) {
        return SseEvent.Skip
    }
    return SseEvent.Delta(
        content = content?.takeIf { it.isNotEmpty() },
        reasoning = reasoning?.takeIf { it.isNotEmpty() },
        toolCalls = toolCalls?.takeIf { it.isNotEmpty() },
        finishReason = choice.finishReason,
    )
}

/**
 * tool_call 增量组装器（对齐 iOS toolBuf 逻辑）：按 index 累积 id/type/name，arguments 逐片拼接。
 * 标准 OpenAI（首片带 id+name、后续只有 arguments 切片）与一次性完整返回都走同一路径。
 * 纯状态机，单测锁契约。
 */
class ToolCallAssembler {
    private class Buf {
        var id: String? = null
        var type: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private val bufs = sortedMapOf<Int, Buf>()

    val isEmpty: Boolean get() = bufs.isEmpty()

    fun add(delta: StreamToolCallDelta) {
        val b = bufs.getOrPut(delta.index) { Buf() }
        delta.id?.let { b.id = it }
        delta.type?.let { b.type = it }
        delta.function?.name?.let { b.name = it }
        delta.function?.arguments?.let { b.arguments.append(it) }
    }

    /** 组装完整调用；缺 id/name/arguments 的残片丢弃（对齐 iOS compactMap 守卫） */
    fun build(): List<WireToolCall> = bufs.values.mapNotNull { b ->
        val id = b.id ?: return@mapNotNull null
        val name = b.name ?: return@mapNotNull null
        if (b.arguments.isEmpty()) return@mapNotNull null
        WireToolCall(id = id, type = b.type ?: "function", function = WireToolCallFunction(name, b.arguments.toString()))
    }
}
