package com.chunland.app.core.ai.domain

/**
 * 流事件（provider 无关，对齐 iOS AgentStreamEvent.swift）。
 *
 * 各家 endpoint 的 SSE 帧格式差异全部压在 provider 实现里，agent 循环只认这套事件。
 * 换 provider = 换一个把自家 wire 翻译成这些事件的适配器，循环本身一行不动。
 */

/** 一个内容块开始了 */
sealed interface AgentBlockStart {
    data object Text : AgentBlockStart
    data class ToolUse(val id: String, val name: String) : AgentBlockStart
    data object Thinking : AgentBlockStart
}

/** 停止原因 */
enum class AgentStopReason(val wire: String) {
    /** 正常结束本回合 */
    END_TURN("end_turn"),

    /** 因为要调工具而暂停 —— 循环应继续 */
    TOOL_USE("tool_use"),

    /** 输出 token 用尽，回复被截断 */
    MAX_TOKENS("max_tokens"),

    /**
     * 模型主动拒答。
     *
     * **必须与 [END_TURN] 分开**：拒答是确定性的，重试同一请求必然再被拒，
     * 只会白烧 token。它不能进重试/降级路径，要直接告诉用户换个说法或换模型。
     */
    REFUSED("refused");

    companion object {
        fun from(raw: String): AgentStopReason? = entries.firstOrNull { it.wire == raw }
    }
}

/** 一次请求的 token 消耗 */
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
) {
    /**
     * 上下文占用量 —— 上下文治理的判断依据。
     * 有 API 返回值时以它为准，没有才回退到本地估算。
     */
    val contextTokens: Int get() = inputTokens + cachedTokens

    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
    )
}

/** 流式事件 */
sealed interface AgentStreamEvent {

    data class BlockStart(val start: AgentBlockStart) : AgentStreamEvent

    data class TextDelta(val text: String) : AgentStreamEvent

    /**
     * 工具参数的增量累积值（完整的 JSON 片段，不是 delta 本身）。
     * 给 UI 做「正在输入参数」的实时预览用；
     * 同时会被留存，供 ToolArgsRepair 在参数截断时尝试补全。
     */
    data class ToolInputDelta(val name: String, val accumulated: String) : AgentStreamEvent

    data class ToolCallComplete(
        val id: String,
        val name: String,
        val input: AgentToolInput,
    ) : AgentStreamEvent

    /** 思考内容增量（实时展示用，不回发） */
    data class ThinkingDelta(val text: String) : AgentStreamEvent

    /** 完整的思考内容（多轮对话需要原样回发时带上） */
    data class Reasoning(val text: String) : AgentStreamEvent

    data class Usage(val usage: TokenUsage) : AgentStreamEvent

    data class Done(val stopReason: AgentStopReason) : AgentStreamEvent
}

/**
 * 一次流式请求的汇总结果。
 *
 * 循环需要的是「这一轮模型说了什么、要调哪些工具、为什么停」，而不是一串事件。
 * provider 负责产事件，本结构由循环侧聚合而成。
 */
data class AgentTurnResult(
    val text: String = "",
    val toolEntries: List<ToolEntry> = emptyList(),
    val reasoning: String? = null,
    val stopReason: AgentStopReason? = null,
    val usage: TokenUsage = TokenUsage(),
    /** 流在收到终止事件前就断了 */
    val isInterrupted: Boolean = false,
) {

    /** 一次工具调用的完整信息 */
    data class ToolEntry(
        val id: String,
        val name: String,
        val input: AgentToolInput,
        /** 参数流的原始累积文本 —— 参数解析失败时，ToolArgsRepair 拿它试补全 */
        val rawInput: String,
    )

    /**
     * 这一轮什么都没产出。
     *
     * 判定要排除三种「看起来空但其实不是异常」的情况：
     * - 有思考内容 → 模型确实工作了
     * - 流被中断 → 走中断路径，不是空响应
     * - 输出截断 / 拒答 → 有明确原因，不该当成空响应去重试
     *
     * 上游 HTTP 200 却什么都不返回是真实存在的失败模式（限流、过载、长上下文），
     * 必须能识别出来才能触发提醒重试。
     */
    val isEmpty: Boolean
        get() = text.isEmpty() &&
            toolEntries.isEmpty() &&
            reasoning.isNullOrEmpty() &&
            !isInterrupted &&
            stopReason != AgentStopReason.MAX_TOKENS &&
            stopReason != AgentStopReason.REFUSED
}
