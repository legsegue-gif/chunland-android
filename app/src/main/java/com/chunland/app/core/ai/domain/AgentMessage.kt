package com.chunland.app.core.ai.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 会话消息的 domain 表示（对齐 iOS AgentMessage.swift）。
 *
 * 这是运行时与业务**唯一认的形状** —— UI、agent 循环、工具管道都只认它。
 * wire 编码（各 provider 的请求体）与 storage（SQLite 表行）都是它的投影，
 * 两侧互不知道对方。
 *
 * 为什么要这一层：旧实现里一个类型同时是 wire 格式、运行时模型、存储 DTO，
 * 三者变化频率完全不同，绑在一起的后果是历史裁剪只能去改 wire 字符串、
 * 图片只能以文本形式塞进消息、工具结果无处携带引用。
 */

/**
 * 媒体引用 —— **字节永远不进这里，也永远不进数据库**。
 * 真实文件由 MediaStore 按内容寻址落盘，这里只带定位信息与元数据。
 */
data class MediaRef(
    val id: String,
    /** 内容哈希（同一张图多次发送只存一份） */
    val sha256: String,
    /** 相对媒体根目录的路径，形如 `ab/abcdef….jpg` */
    val relPath: String,
    val mime: String,
    val bytes: Int,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * 消息里的一段内容。
 *
 * [ToolResult] 与 [ToolUse] 通过 `id` 配对 —— 这是整个 agent 循环的**第一约束**：
 * 调用与结果必须严格配对且同序，历史裁剪 / 压缩 / 卸载 / 重试 / 取消全都不能破坏它。
 */
sealed interface AgentContentPart {

    /** 普通文本（用户输入或模型输出） */
    data class Text(val text: String) : AgentContentPart

    /** 模型发起的工具调用 */
    data class ToolUse(
        val id: String,
        val name: String,
        val input: AgentToolInput,
    ) : AgentContentPart

    /**
     * 工具执行结果。
     *
     * @param media 工具产出的图片，无则 null
     * @param offloadRef 非空表示 [text] 已被替换成占位说明，原文可按此 ref 从 OffloadStore 取回
     */
    data class ToolResult(
        val id: String,
        val name: String,
        val text: String,
        val isError: Boolean,
        val media: MediaRef? = null,
        val offloadRef: String? = null,
    ) : AgentContentPart

    /** 用户附带的图片 */
    data class Image(val media: MediaRef) : AgentContentPart
}

/**
 * 工具调用参数。
 *
 * 直接包 kotlinx 的 [JsonObject]：Kotlin 侧已有成熟的 JSON 值模型，
 * 不必像 iOS 那样自建（Swift 没有内置的 JSON 值类型，用 `[String: Any]`
 * 会因 `Any` 不是 `Sendable` 而让整条链路被迫 `@unchecked`）。
 * 两端的**结构**一致即可，不必强求实现手段一致。
 */
data class AgentToolInput(val json: JsonObject = JsonObject(emptyMap())) {

    val isEmpty: Boolean get() = json.isEmpty()
    val keys: Set<String> get() = json.keys

    operator fun get(key: String): JsonElement? = json[key]

    fun string(key: String): String? = when (val v = json[key]) {
        null, JsonNull -> null
        is JsonPrimitive ->
            // 模型偶尔把字符串字段发成数字/布尔，这里宽容读取；
            // 真正的修复在 ToolArgsRepair，此处只是让 handler 不必到处判类型。
            if (v.isString) v.content else v.content
        else -> null
    }

    fun int(key: String): Int? = (json[key] as? JsonPrimitive)?.let {
        it.intOrNull ?: it.content.toIntOrNull()
    }

    fun double(key: String): Double? = (json[key] as? JsonPrimitive)?.let {
        it.doubleOrNull ?: it.content.toDoubleOrNull()
    }

    fun bool(key: String): Boolean? = (json[key] as? JsonPrimitive)?.let {
        it.booleanOrNull ?: when (it.content) {
            "true" -> true
            "false" -> false
            else -> it.intOrNull?.let { n -> n != 0 }
        }
    }

    /**
     * 是否是「空白」值 —— preflight 判定必填字段是否形同缺失时用。
     * 模型常发 `{"path": ""}`，键在但没内容，和缺键一样坏。
     */
    fun isBlank(key: String): Boolean {
        val v = json[key] ?: return true
        return when (v) {
            JsonNull -> true
            is JsonPrimitive -> v.content.isBlank()
            is JsonObject -> v.isEmpty()
            else -> (v as? kotlinx.serialization.json.JsonArray)?.isEmpty() ?: false
        }
    }

    /** 序列化回 JSON 串（落库与 wire 编码共用） */
    fun jsonString(): String = json.toString()

    /** 供 ToolArgsRepair 修改后回填（不可变，返回新实例） */
    fun with(key: String, value: JsonElement): AgentToolInput =
        AgentToolInput(JsonObject(json.toMutableMap().apply { put(key, value) }))

    fun without(key: String): AgentToolInput =
        AgentToolInput(JsonObject(json.toMutableMap().apply { remove(key) }))

    fun renamed(from: String, to: String): AgentToolInput {
        val v = json[from] ?: return this
        return AgentToolInput(
            JsonObject(json.toMutableMap().apply { remove(from); put(to, v) })
        )
    }

    companion object {
        /**
         * 从工具调用的原始 JSON 串解析。
         *
         * 解析失败返回空参数 —— 由 ToolArgsRepair 与 preflight 接手处理，
         * 此处不抛错（模型发来的 JSON 截断是常态而非异常）。
         */
        fun parse(raw: String): AgentToolInput = runCatching {
            AgentToolInput(kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject)
        }.getOrElse { AgentToolInput() }
    }
}

/**
 * 一条会话消息。
 *
 * **只有 user / assistant 两种角色，没有 tool。** 工具结果是 user 消息里的
 * [AgentContentPart.ToolResult]。传输层的独立 `role: "tool"` 帧由 wire 编码时才拆出来
 * —— domain 不关心传输格式。
 */
data class AgentMessage(
    val role: Role,
    val parts: List<AgentContentPart>,
    /**
     * 流式生成中途断开（网络掉线 / 进程被杀）。
     *
     * 这种消息里的工具调用参数可能残缺（JSON 没写完），回发会被上游拒绝。
     * agent 循环在每轮开始前据此**丢弃**尾部的中断消息，从上一回合重来。
     */
    val isInterrupted: Boolean = false,
    /** 部分模型返回的思考内容。多轮对话需要原样回发时才带上 */
    val reasoning: String? = null,
    /**
     * 落库后回填的 messages 表主键。
     * 压缩与卸载据此定位消息边界 —— 用 id 而不是列表下标，因为历史随时可能被裁剪。
     */
    val dbId: String? = null,
) {

    enum class Role(val wire: String) {
        USER("user"),
        ASSISTANT("assistant");

        companion object {
            fun from(raw: String): Role? = entries.firstOrNull { it.wire == raw }
        }
    }

    /** 拼接全部文本片段（UI 预览、标题派生、检索入库共用） */
    val plainText: String
        get() = parts.filterIsInstance<AgentContentPart.Text>()
            .joinToString("\n") { it.text }

    val toolUses: List<AgentContentPart.ToolUse>
        get() = parts.filterIsInstance<AgentContentPart.ToolUse>()

    val toolResultIds: List<String>
        get() = parts.filterIsInstance<AgentContentPart.ToolResult>().map { it.id }

    val hasToolUse: Boolean get() = toolUses.isNotEmpty()

    /**
     * 是否整条消息都是工具结果 —— 用于判断「最后一条是不是工具结果」，
     * 这是空响应重试的触发条件之一。
     */
    val isPureToolResult: Boolean
        get() = parts.isNotEmpty() && parts.all { it is AgentContentPart.ToolResult }

    companion object {
        fun user(text: String) = AgentMessage(Role.USER, listOf(AgentContentPart.Text(text)))

        fun user(text: String, media: List<MediaRef>) = AgentMessage(
            Role.USER,
            buildList {
                if (text.isNotEmpty()) add(AgentContentPart.Text(text))
                media.forEach { add(AgentContentPart.Image(it)) }
            }
        )

        fun assistant(text: String) =
            AgentMessage(Role.ASSISTANT, listOf(AgentContentPart.Text(text)))

        /** 一批工具结果打成一条 user 消息 —— 必须与上一条 assistant 的调用顺序一致 */
        fun toolResults(parts: List<AgentContentPart>) = AgentMessage(Role.USER, parts)
    }
}

/**
 * 配对完整性（对齐 iOS AgentHistoryIntegrity）。
 *
 * 「调用与结果严格配对同序」是第一约束，但历史会被裁剪、压缩、卸载、取消打断，
 * 孤儿是必然会出现的。所以每轮进入循环前都要做一次双向扫描修复，
 * 而不是指望每条产生路径都不出错。
 */
object AgentHistoryIntegrity {

    data class Report(
        /** 有调用无结果 —— 需要补占位结果，否则模型永远等不到回应 */
        val orphanToolUses: List<Triple<Int, String, String>> = emptyList(),
        /** 有结果无调用 —— 必须删掉，否则上游报「未知的 tool_use_id」 */
        val orphanToolResults: List<Pair<Int, String>> = emptyList(),
        /** 尾部中断的 assistant 消息（参数可能残缺，整条丢弃） */
        val trailingInterruptedIndex: Int? = null,
    ) {
        val isClean: Boolean
            get() = orphanToolUses.isEmpty() &&
                orphanToolResults.isEmpty() &&
                trailingInterruptedIndex == null
    }

    /** 只扫描不修改 —— 修复动作由调用方执行，便于分别记日志 */
    fun scan(history: List<AgentMessage>): Report {
        val trailing = history.lastOrNull()
            ?.takeIf { it.role == AgentMessage.Role.ASSISTANT && it.isInterrupted }
            ?.let { history.size - 1 }

        val useIds = mutableSetOf<String>()
        val resultIds = mutableSetOf<String>()
        history.forEach { msg ->
            msg.parts.forEach { part ->
                when (part) {
                    is AgentContentPart.ToolUse -> useIds += part.id
                    is AgentContentPart.ToolResult -> resultIds += part.id
                    else -> Unit
                }
            }
        }

        val orphanUses = mutableListOf<Triple<Int, String, String>>()
        val orphanResults = mutableListOf<Pair<Int, String>>()
        history.forEachIndexed { i, msg ->
            msg.parts.forEach { part ->
                when {
                    part is AgentContentPart.ToolUse && part.id !in resultIds ->
                        orphanUses += Triple(i, part.id, part.name)
                    part is AgentContentPart.ToolResult && part.id !in useIds ->
                        orphanResults += i to part.id
                    else -> Unit
                }
            }
        }
        return Report(orphanUses, orphanResults, trailing)
    }

    /**
     * 按扫描结果修复，返回修复后的历史。
     *
     * 顺序不能变：先丢中断消息，它带走的调用就不必再补占位了。
     */
    fun repair(history: List<AgentMessage>): List<AgentMessage> {
        var working = history

        scan(working).trailingInterruptedIndex?.let { idx ->
            working = working.filterIndexed { i, _ -> i != idx }
        }

        val orphanResults = scan(working).orphanToolResults.map { it.second }.toSet()
        if (orphanResults.isNotEmpty()) {
            working = working.mapNotNull { msg ->
                val kept = msg.parts.filterNot {
                    it is AgentContentPart.ToolResult && it.id in orphanResults
                }
                when {
                    kept.isEmpty() -> null
                    kept.size != msg.parts.size -> msg.copy(parts = kept)
                    else -> msg
                }
            }
        }

        // 补占位：紧跟在产生该调用的 assistant 消息之后插入，保持同序。
        val orphanUses = scan(working).orphanToolUses
        if (orphanUses.isNotEmpty()) {
            val byMessage = orphanUses.groupBy({ it.first }) { it.second to it.third }
            val out = mutableListOf<AgentMessage>()
            working.forEachIndexed { i, msg ->
                out += msg
                byMessage[i]?.let { uses ->
                    out += AgentMessage.toolResults(
                        uses.map { (id, name) ->
                            AgentContentPart.ToolResult(
                                id = id, name = name,
                                text = "工具执行被意外中断，未能取得结果。",
                                isError = true,
                            )
                        }
                    )
                }
            }
            working = out
        }
        return working
    }
}
