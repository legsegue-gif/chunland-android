package com.chunland.app.core.ai.provider

import com.chunland.app.core.ai.domain.AgentMessage
import com.chunland.app.core.ai.domain.AgentStreamEvent
import com.chunland.app.core.ai.domain.AgentToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

/**
 * 双协议（对齐 iOS AgentProvider.swift）。
 *
 * 把「调 LLM」切成两个接口，而不是一个：
 * - [LlmProvider]：单次调用 —— 会话标题生成、上下文压缩摘要、商家 AI 分类
 * - [AgentProvider]：agent 循环 —— 带工具、多轮、流式
 *
 * 两者共用同一套 provider 实现与配置源。这正是当前代码分散的根因所在：
 * 单次调用的场景因为「不是对话」而自己写了一套 endpoint 解析 + SSE 解析 +
 * 错误处理，与对话链路完全重复。切成双接口后，它只是换个接口调同一个实现，
 * 顺带白拿重试与降级能力。
 */

/** 单次调用的一轮消息（比 AgentMessage 轻，没有工具与媒体） */
data class LlmTurn(val role: Role, val content: String) {
    enum class Role(val wire: String) {
        SYSTEM("system"), USER("user"), ASSISTANT("assistant")
    }

    companion object {
        fun user(text: String) = LlmTurn(Role.USER, text)
        fun assistant(text: String) = LlmTurn(Role.ASSISTANT, text)
    }
}

/** 单次调用：给一段输入，拿一段输出。无工具、无多轮 */
interface LlmProvider {
    /** 本次调用实际使用的模型标识（日志与降级提示用） */
    val modelId: String

    /**
     * 流式返回文本增量。
     *
     * 即使调用方只想要完整结果也走流式：**不押注上游实现了非流式**。
     * 实测中不少 OpenAI 兼容端点的非流式分支要么没实现、要么行为不一致，
     * 而流式是它们的主路径。聚合成整段由调用方决定。
     */
    fun streamText(
        messages: List<LlmTurn>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null,
    ): Flow<String>
}

/** 聚合成完整文本 —— 单次结构化调用（如商家分类）用这个 */
suspend fun LlmProvider.completeText(
    messages: List<LlmTurn>,
    systemPrompt: String? = null,
    maxTokens: Int = 4096,
    temperature: Double? = null,
): String = streamText(messages, systemPrompt, maxTokens, temperature).toList().joinToString("")

/** agent 循环：流式 + 工具调用 */
interface AgentProvider {
    val modelId: String

    /** 该模型的默认最大输出 token */
    val defaultMaxTokens: Int

    /**
     * 开一条流。
     *
     * 实现只负责「把自家 wire 翻译成 [AgentStreamEvent]」，
     * 不做重试、不做降级、不碰历史 —— 那些是循环层的职责。
     */
    fun streamAgent(
        messages: List<AgentMessage>,
        systemPrompt: String?,
        tools: List<AgentToolDefinition>,
        maxTokens: Int,
    ): Flow<AgentStreamEvent>
}

/** 从 agent 事件流里只取文本增量（单次调用复用 agent 传输实现时用） */
fun Flow<AgentStreamEvent>.textDeltas(): Flow<String> =
    filterIsInstance<AgentStreamEvent.TextDelta>().map { it.text }
