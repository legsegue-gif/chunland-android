package com.chunland.app.core.ai.provider

import com.chunland.app.core.ai.domain.AgentContentPart
import com.chunland.app.core.ai.domain.AgentMessage
import com.chunland.app.core.ai.domain.AgentStopReason
import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.domain.AgentToolInput
import com.chunland.app.core.ai.domain.AgentTurnResult
import com.chunland.app.core.ai.domain.MediaRef
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * domain ↔ OpenAI wire 映射（对齐 iOS OpenAIWire.swift）。
 *
 * 这一层是 domain 与传输格式之间**唯一**的接触面。
 * domain 只有 user / assistant 两种角色，工具结果是 user 消息里的一个片段；
 * OpenAI 协议里工具结果是独立的 `role: "tool"` 帧 —— 转换在这里发生。
 *
 * 顺序约束：`role: "tool"` 帧的顺序必须与前一条 assistant 的 `tool_calls` 一致。
 * 并发执行工具时按索引缝合结果就是为了保证这一点。
 *
 * 手工构 JsonObject 而不是用 @Serializable data class：wire 上有大量
 * 「有则带、无则整个字段不出现」的可选项，用 explicitNulls=false + 一堆
 * nullable 字段表达反而更绕，而且容易在默认值上翻车（stream=true 被吞过一次）。
 */
object OpenAiWire {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // MARK: - 请求体

    fun requestBody(
        model: String,
        messages: List<JsonObject>,
        tools: List<AgentToolDefinition>,
        maxTokens: Int?,
        temperature: Double?,
        stream: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", JsonArray(messages))
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray { tools.forEach { add(it.openAISchema()) } })
            put("tool_choice", "auto")
        }
        maxTokens?.let { put("max_tokens", it) }
        temperature?.let { put("temperature", it) }
        // stream 必须显式落 wire —— 用默认值省略会让部分端点退化成非流式，
        // 表现是「一直转圈然后整段吐出来」。
        put("stream", stream)
    }

    // MARK: - 编码：domain → wire

    /**
     * 把 domain 历史编成 wire 消息数组。
     *
     * [loadImage] 由调用方提供 —— 图片字节**只在编码这一刻**读进内存，
     * 编完即弃，绝不驻留在 domain 或存储里。
     * 模型不支持视觉时传 null，图片会被降级成一行文字说明。
     */
    fun encode(
        messages: List<AgentMessage>,
        systemPrompt: String?,
        loadImage: ((MediaRef) -> ByteArray?)?,
    ): List<JsonObject> = buildList {
        if (!systemPrompt.isNullOrEmpty()) {
            add(textMessage("system", systemPrompt))
        }
        messages.forEach { msg ->
            when (msg.role) {
                AgentMessage.Role.ASSISTANT -> addAll(encodeAssistant(msg))
                AgentMessage.Role.USER -> addAll(encodeUser(msg, loadImage))
            }
        }
    }

    /** 单次调用的轻量编码 */
    fun encode(turns: List<LlmTurn>, systemPrompt: String?): List<JsonObject> = buildList {
        if (!systemPrompt.isNullOrEmpty()) add(textMessage("system", systemPrompt))
        turns.forEach { add(textMessage(it.role.wire, it.content)) }
    }

    private fun textMessage(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun encodeAssistant(msg: AgentMessage): List<JsonObject> {
        var text = ""
        val calls = mutableListOf<JsonObject>()
        msg.parts.forEach { part ->
            when (part) {
                is AgentContentPart.Text ->
                    text = if (text.isEmpty()) part.text else "$text\n${part.text}"
                is AgentContentPart.ToolUse -> calls += buildJsonObject {
                    put("id", part.id)
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", part.name)
                        put("arguments", part.input.jsonString())
                    }
                }
                else -> Unit
            }
        }
        // 既无文本也无工具调用的 assistant 帧会被部分端点判为非法，直接丢掉
        if (text.isEmpty() && calls.isEmpty()) return emptyList()

        return listOf(
            buildJsonObject {
                put("role", "assistant")
                if (text.isNotEmpty()) put("content", text)
                if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls))
            }
        )
    }

    private fun encodeUser(
        msg: AgentMessage,
        loadImage: ((MediaRef) -> ByteArray?)?,
    ): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var parts = mutableListOf<JsonObject>()

        fun flushParts() {
            if (parts.isEmpty()) return
            // 纯文本时编成字符串而不是数组 —— 兼容性更好，
            // 有的端点对 content 数组的支持只覆盖了带图场景。
            val single = parts.singleOrNull()
            if (single != null && single["type"]?.jsonPrimitive?.contentOrNull == "text") {
                out += textMessage("user", single["text"]?.jsonPrimitive?.contentOrNull ?: "")
            } else {
                out += buildJsonObject {
                    put("role", "user")
                    put("content", JsonArray(parts))
                }
            }
            parts = mutableListOf()
        }

        msg.parts.forEach { part ->
            when (part) {
                is AgentContentPart.Text -> parts += buildJsonObject {
                    put("type", "text")
                    put("text", part.text)
                }

                is AgentContentPart.Image -> {
                    val bytes = loadImage?.invoke(part.media)
                    if (bytes != null) {
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        parts += buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:${part.media.mime};base64,$b64") }
                        }
                    } else {
                        // 模型不支持视觉、或文件已丢失：给一行说明而不是静默丢弃，
                        // 否则模型会认为用户什么都没发。
                        parts += buildJsonObject {
                            put("type", "text")
                            put("text", "（此处有一张图片，当前模型无法查看）")
                        }
                    }
                }

                is AgentContentPart.ToolResult -> {
                    // 工具结果必须**单独成帧**，且要排在它之前累积的 parts 之后
                    flushParts()
                    var body = part.text
                    // OpenAI 协议的 tool 帧不支持多模态内容，只能补一句说明
                    if (part.media != null) body += "\n（该结果附带一张图片）"
                    out += buildJsonObject {
                        put("role", "tool")
                        put("content", body)
                        put("tool_call_id", part.id)
                    }
                }

                // 卡片是给用户看的，**绝不上 wire** —— 它不参与模型推理，
                // 上了只是白占上下文，而且模型会开始转述卡片里的数字
                is AgentContentPart.Cards -> Unit
                is AgentContentPart.ToolUse -> Unit   // user 消息里不应出现工具调用
            }
        }
        flushParts()
        return out
    }

    // MARK: - 解码：SSE chunk

    data class Delta(
        val content: String?,
        val reasoning: String?,
        val toolCalls: List<ToolCallDelta>,
        val finishReason: String?,
    )

    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String?,
    )

    data class Usage(val input: Int, val output: Int, val cached: Int)

    /** 解析一个 SSE data 帧。返回 null 表示这帧没有可用内容 */
    fun parseChunk(raw: String): Pair<Delta?, Usage?>? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null

        val usage = root["usage"]?.jsonObject?.let { u ->
            Usage(
                input = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                output = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                cached = u["prompt_tokens_details"]?.jsonObject
                    ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            )
        }

        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        if (choice == null) return (null to usage).takeIf { usage != null }

        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        val d = choice["delta"]?.jsonObject

        val calls = d?.get("tool_calls")?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val idx = o["index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val fn = o["function"]?.jsonObject
            ToolCallDelta(
                index = idx,
                id = o["id"]?.jsonPrimitive?.contentOrNull,
                name = fn?.get("name")?.jsonPrimitive?.contentOrNull,
                arguments = fn?.get("arguments")?.jsonPrimitive?.contentOrNull,
            )
        } ?: emptyList()

        // 思考内容：两种字段名都见过，取先有的那个
        val reasoning = d?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?: d?.get("reasoning")?.jsonPrimitive?.contentOrNull

        return Delta(
            content = d?.get("content")?.jsonPrimitive?.contentOrNull,
            reasoning = reasoning,
            toolCalls = calls,
            finishReason = finish,
        ) to usage
    }

    /** SSE 里内嵌的错误帧（HTTP 200 但 body 是错误）。返回 null 表示不是错误帧 */
    fun parseErrorPayload(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        root["error"]?.let { err ->
            val obj = err as? JsonObject ?: return err.jsonPrimitive.contentOrNull ?: "未知错误"
            return obj["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
        }
        // 顶层 message 且无 choices，才认为是错误帧（正常 chunk 也可能带 message 字段）
        if (root["choices"] == null) {
            return root["message"]?.jsonPrimitive?.contentOrNull
        }
        return null
    }

    /** `finish_reason` → 停止原因 */
    fun stopReason(raw: String?): AgentStopReason? = when (raw) {
        "stop" -> AgentStopReason.END_TURN
        "tool_calls", "function_call" -> AgentStopReason.TOOL_USE
        "length", "max_tokens" -> AgentStopReason.MAX_TOKENS
        "content_filter" -> AgentStopReason.REFUSED
        null -> null
        else -> AgentStopReason.END_TURN
    }
}

/**
 * 工具调用分片累积（对齐 iOS ToolCallAssembler）。
 *
 * 两种上游实现都要吃：
 * - 标准 OpenAI —— arguments 按字符切片，按 index 累积
 * - 部分兼容端 —— 一次性给完整的 tool_calls
 *
 * 同一套 buffer 逻辑对两者都成立。
 */
class ToolCallAssembler {

    private class Slot {
        var id: String = ""
        var name: String = ""
        var arguments: StringBuilder = StringBuilder()
    }

    private val slots = sortedMapOf<Int, Slot>()

    fun accept(deltas: List<OpenAiWire.ToolCallDelta>) {
        deltas.forEach { d ->
            val slot = slots.getOrPut(d.index) { Slot() }
            d.id?.takeIf { it.isNotEmpty() }?.let { slot.id = it }
            d.name?.takeIf { it.isNotEmpty() }?.let { slot.name = it }
            d.arguments?.let { slot.arguments.append(it) }
        }
    }

    /** 某个槽位当前累积的原始参数文本（供实时预览与截断修复） */
    fun rawArguments(index: Int): String? = slots[index]?.arguments?.toString()

    val isEmpty: Boolean get() = slots.isEmpty()

    /** 按 index 升序产出 —— 顺序即 wire 上的顺序，工具结果必须按同序回填 */
    fun finish(): List<AgentTurnResult.ToolEntry> = slots.values.mapNotNull { slot ->
        if (slot.name.isEmpty()) return@mapNotNull null
        // id 缺失时补一个：某些端点在单工具调用时省略 id，
        // 但我们的配对约束要求它必须存在。
        val id = slot.id.ifEmpty { "call_${UUID.randomUUID().toString().take(8)}" }
        val raw = slot.arguments.toString()
        AgentTurnResult.ToolEntry(
            id = id,
            name = slot.name,
            input = AgentToolInput.parse(raw),
            rawInput = raw,
        )
    }
}
