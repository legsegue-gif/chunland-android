package com.chunland.app.core.ai.loop

import android.util.Log
import com.chunland.app.core.ai.domain.AgentCard
import com.chunland.app.core.ai.domain.AgentContentPart
import com.chunland.app.core.ai.domain.AgentHistoryIntegrity
import com.chunland.app.core.ai.domain.AgentMessage
import com.chunland.app.core.ai.domain.AgentStopReason
import com.chunland.app.core.ai.domain.AgentStreamEvent
import com.chunland.app.core.ai.domain.AgentTurnResult
import com.chunland.app.core.ai.domain.TokenUsage
import com.chunland.app.core.ai.prompt.AiPrompts
import com.chunland.app.core.ai.provider.FallbackRecord
import com.chunland.app.core.ai.provider.LlmError
import com.chunland.app.core.ai.provider.ProviderRouter
import com.chunland.app.core.ai.provider.SessionModelBinding
import com.chunland.app.core.logging.AiDebugFileLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * agent 循环（对齐 iOS AgentLoop.swift）。
 *
 * 一次「用户发送 → 得到最终答复」的完整过程。
 *
 * 与旧实现最大的差别不是轮次从 8 放到 30，而是**每条退出路径都有可解释的状态**：
 * 旧实现触顶只会回一句「查询轮次过多，请换个问题试试」，用户不知道做到哪了、
 * 该怎么办；空响应只回「AI 返回了空响应，请重试」，而那多半是上游过载、
 * 重试一次就好。
 */

/** 循环对外发出的事件。UI 订阅它渲染，不关心内部机制 */
sealed interface AgentLoopEvent {
    data class TurnStarted(val index: Int) : AgentLoopEvent
    data class TextDelta(val text: String) : AgentLoopEvent
    data class ThinkingDelta(val text: String) : AgentLoopEvent

    /** 工具开始执行。[title] 是模型自述的「在做什么」 */
    data class ToolStarted(val id: String, val name: String, val title: String?) : AgentLoopEvent
    /**
     * 工具执行完毕。[resultText] 是原始结果（带围栏），UI 拿它成摘要 ——
     * 不带的话流式期间那个块就只有状态没有内容，展不开。
     */
    data class ToolFinished(
        val id: String,
        val name: String,
        val isError: Boolean,
        val resultText: String?,
    ) : AgentLoopEvent

    /**
     * 结构化卡片（R3）—— **给用户看的那一份，不喂给模型**。
     * 与 Cards 历史 part 是同一批数据：这个事件负责流式期间的展示，
     * 历史 part 负责重开会话后仍在。
     */
    data class Cards(val cards: List<AgentCard>) : AgentLoopEvent

    /** 发生了模型降级，应告知用户 */
    data class Fallback(val record: FallbackRecord) : AgentLoopEvent

    /** 上下文被压缩了 */
    data object Compacted : AgentLoopEvent

    /** 一轮的 token 用量 */
    data class Usage(val usage: TokenUsage) : AgentLoopEvent

    data class Finished(val end: AgentLoopEnd) : AgentLoopEvent
}

/** 循环为什么结束。**每种都要能向用户解释清楚** */
sealed interface AgentLoopEnd {
    /** 正常给出了答复 */
    data object Completed : AgentLoopEnd

    /** 连续执行到轮次上限仍未收敛。可续跑 */
    data class TurnLimit(val limit: Int) : AgentLoopEnd

    /** 上下文压不动了，需要新开会话 */
    data object ContextExhausted : AgentLoopEnd

    /** 模型输出被截断（max_tokens）。可续跑 */
    data object Truncated : AgentLoopEnd

    /** 模型拒答 —— 确定性的，重试无用 */
    data object Refused : AgentLoopEnd

    /** 用户取消 */
    data object Cancelled : AgentLoopEnd

    data class Failed(val message: String) : AgentLoopEnd

    /** 是否可以从当前状态继续跑 */
    val isResumable: Boolean
        get() = this is TurnLimit || this is Truncated || this is Cancelled
}

class AgentLoop(
    private val router: ProviderRouter,
    private val pipeline: AgentToolPipeline,
    private val executor: AgentToolExecuting,
    private val detector: ToolLoopDetector,
    /**
     * 生成历史摘要（单次调用协议）。
     *
     * 由装配层注入而不是循环自己建 provider —— 循环不认识配置与来源。
     * 为 null 时压缩退化成占位说明：能腾出空间，但会丢掉较早对话的内容。
     */
    private val summarize: (suspend (String) -> String)? = null,
) {

    companion object {
        private const val TAG = "AgentLoop"

        /**
         * 轮次上限。
         *
         * 按实际业务链路推算而非拍脑袋：最长的是商家「按吃穿住行分类」
         * （读商品 → 建方案 → 逐分类归类），约 10-15 轮，留一倍余量。
         * 旧实现的 8 轮在这个场景下必然触顶。
         */
        const val MAX_TURNS = 30

        /**
         * 循环内压缩的次数上限。压缩是空间管理动作，不占轮次预算，
         * 但总上限永不重置 —— 否则一个反复压缩的循环能永远跑下去。
         */
        const val MAX_COMPACTIONS = 3

        /** 历史里保留的图片张数。更早的换成可重取的占位 */
        const val KEEP_IMAGES = 8
    }

    /** 会话历史（内存态，落库由调用方在事件回调里做） */
    private val history = mutableListOf<AgentMessage>()

    /** 上一轮 API 返回的真实上下文用量。有它就不用估算 */
    private var lastContextTokens = 0

    /** 本次发送是否已经注入过空响应提醒 —— 每次发送只允许一次，物理上不会循环 */
    private var didInjectEmptyReminder = false

    // MARK: - 历史

    fun setHistory(messages: List<AgentMessage>) {
        history.clear()
        history += messages
    }

    fun currentHistory(): List<AgentMessage> = history.toList()

    fun reset() {
        history.clear()
        lastContextTokens = 0
        detector.reset()
    }

    // MARK: - 主循环

    /** 跑一轮完整对话。返回事件流 */
    fun run(
        userMessage: AgentMessage,
        binding: SessionModelBinding?,
        systemPrompt: String,
        contextWindow: Int,
    ): Flow<AgentLoopEvent> = flow {
        history += userMessage
        didInjectEmptyReminder = false

        val policy = ContextPolicy(contextWindow)
        val tools = executor.availableTools()
        var compactions = 0
        var turn = 0

        while (turn < MAX_TURNS) {
            if (!currentCoroutineContext().isActive) {
                emit(AgentLoopEvent.Finished(AgentLoopEnd.Cancelled))
                return@flow
            }

            // 进入前修复配对：历史被裁剪、取消打断、流中断都会制造孤儿，
            // 带着孤儿发请求会被上游 400 拒。修复而不是指望每条路径都不出错。
            val report = AgentHistoryIntegrity.scan(history)
            if (!report.isClean) {
                val repaired = AgentHistoryIntegrity.repair(history)
                history.clear()
                history += repaired
                Log.w(TAG, "修复了历史配对 orphanUses=${report.orphanToolUses.size} " +
                    "orphanResults=${report.orphanToolResults.size} " +
                    "droppedInterrupted=${report.trailingInterruptedIndex != null}")
            }

            trimOldImages()

            // 上下文治理：先用便宜手段（卸载），不够再用贵的（压缩）
            val used = if (lastContextTokens > 0) lastContextTokens else TokenEstimator.estimate(history)
            when (policy.decide(used)) {
                ContextPolicy.Decision.OK -> Unit
                ContextPolicy.Decision.NEEDS_COMPACT -> {
                    if (compactions >= MAX_COMPACTIONS) {
                        Log.w(TAG, "压缩次数用尽仍接近上限，按耗尽处理")
                        emit(AgentLoopEvent.Finished(AgentLoopEnd.ContextExhausted))
                        return@flow
                    }
                    compactions++
                    // 压缩是空间管理动作，不消耗轮次预算 —— 但总上限不重置
                    compactHistory()
                    emit(AgentLoopEvent.Compacted)
                    continue
                }
                ContextPolicy.Decision.EXHAUSTED -> {
                    emit(AgentLoopEvent.Finished(AgentLoopEnd.ContextExhausted))
                    return@flow
                }
            }

            turn++
            emit(AgentLoopEvent.TurnStarted(turn))

            // 开流（内含重试与降级）
            val routed = try {
                router.stream(binding, history.toList(), systemPrompt, tools)
            } catch (e: Throwable) {
                val error = LlmError.fromThrowable(e)
                AiDebugFileLog.response(
                    outcome = if (error.isCancellation) "cancelled" else "openFailed",
                    text = null,
                    detail = error.userMessage,
                )
                emit(AgentLoopEvent.Finished(
                    if (error.isCancellation) AgentLoopEnd.Cancelled
                    else AgentLoopEnd.Failed(error.userMessage)
                ))
                return@flow
            }
            routed.fallbacks.forEach { emit(AgentLoopEvent.Fallback(it)) }

            // 请求落盘放在开流之后：model 要用**实际选中的**那个（可能已经降级过），
            // 开流前记等于记了一个可能没被用上的模型。对齐 iOS AgentLoop.swift 的同一处注释。
            AiDebugFileLog.request(
                model = routed.entry.modelId,
                toolNames = tools.map { it.name },
                messages = history.toList(),
            )

            // 消费流
            val result = try {
                consume(routed.stream) { emit(it) }
            } catch (e: Throwable) {
                val error = LlmError.fromThrowable(e)
                AiDebugFileLog.response(
                    outcome = if (error.isCancellation) "cancelled" else "streamFailed",
                    text = null,
                    detail = error.userMessage,
                )
                emit(AgentLoopEvent.Finished(
                    if (error.isCancellation) AgentLoopEnd.Cancelled
                    else AgentLoopEnd.Failed(error.userMessage)
                ))
                return@flow
            }

            // 本轮产出（工具调用也是产出的一种形态）
            AiDebugFileLog.response(
                outcome = if (result.toolEntries.isEmpty()) {
                    result.stopReason?.name ?: "interrupted"
                } else {
                    "toolCalls"
                },
                text = result.text,
                toolNames = result.toolEntries.map { it.name },
            )

            lastContextTokens = result.usage.contextTokens
            if (result.usage.contextTokens > 0) emit(AgentLoopEvent.Usage(result.usage))

            // 空响应：上游过载 / 长上下文的真实失败模式，表现是聊天静默停止。
            // 注入一次提醒重试一轮，每次发送只允许一次。
            if (result.isEmpty) {
                if (!didInjectEmptyReminder && history.lastOrNull()?.isPureToolResult == true) {
                    didInjectEmptyReminder = true
                    Log.w(TAG, "空响应，注入提醒重试一轮")
                    history += AgentMessage.user(AiPrompts.emptyResponseReminder)
                    turn--   // 提醒重试不算一轮任务迭代
                    continue
                }
                emit(AgentLoopEvent.Finished(
                    AgentLoopEnd.Failed("AI 没有返回内容，可能是服务繁忙，请重试或换个模型。")
                ))
                return@flow
            }

            // 落进历史
            val assistantParts = buildList {
                if (result.text.isNotEmpty()) add(AgentContentPart.Text(result.text))
                result.toolEntries.forEach {
                    add(AgentContentPart.ToolUse(it.id, it.name, it.input))
                }
            }
            history += AgentMessage(
                role = AgentMessage.Role.ASSISTANT,
                parts = assistantParts,
                isInterrupted = result.stopReason == null,
                reasoning = result.reasoning,
            )

            // 没有工具调用 = 这一轮给出了答复
            if (result.toolEntries.isEmpty()) {
                emit(AgentLoopEvent.Finished(
                    when (result.stopReason) {
                        AgentStopReason.MAX_TOKENS -> AgentLoopEnd.Truncated
                        AgentStopReason.REFUSED -> AgentLoopEnd.Refused
                        null -> AgentLoopEnd.Truncated   // 流中断，可续跑
                        else -> AgentLoopEnd.Completed
                    }
                ))
                return@flow
            }

            // 执行工具
            val outcomes = pipeline.executeBatch(result.toolEntries, tools)
            outcomes.forEach { outcome ->
                emit(AgentLoopEvent.ToolStarted(outcome.toolId, outcome.toolName, outcome.title))
                emit(AgentLoopEvent.ToolFinished(
                    outcome.toolId, outcome.toolName, outcome.isError,
                    (outcome.part as? AgentContentPart.ToolResult)?.text,
                ))
            }
            // 卡片挂到刚才那条 assistant 上 —— 它是「这一轮助手做了什么」的载体。
            // wire 层会跳过 Cards，所以不占上下文、模型也无从转述卡片里的数字。
            val cards = executor.drainCards()
            if (cards.isNotEmpty() && history.isNotEmpty()) {
                val last = history.removeAt(history.lastIndex)
                history += last.copy(parts = last.parts + AgentContentPart.Cards(cards))
                emit(AgentLoopEvent.Cards(cards))   // 流式期间也要立刻显示，不能等重开会话
            }
            history += AgentMessage.toolResults(outcomes.map { it.part })

            if (outcomes.any { it.cancelled } || !currentCoroutineContext().isActive) {
                // 工具结果已经落进历史，配对是完整的，可以安全停在这里
                emit(AgentLoopEvent.Finished(AgentLoopEnd.Cancelled))
                return@flow
            }
        }

        // 触顶：说清为什么停、可以怎么办
        Log.w(TAG, "触到轮次上限 limit=$MAX_TURNS")
        emit(AgentLoopEvent.Finished(AgentLoopEnd.TurnLimit(MAX_TURNS)))
    }

    // MARK: - 消费流

    private suspend fun consume(
        stream: Flow<AgentStreamEvent>,
        emit: suspend (AgentLoopEvent) -> Unit,
    ): AgentTurnResult {
        val text = StringBuilder()
        var reasoning = StringBuilder()
        val entries = mutableListOf<AgentTurnResult.ToolEntry>()
        var usage = TokenUsage()
        var stopReason: AgentStopReason? = null

        stream.collect { event ->
            when (event) {
                is AgentStreamEvent.TextDelta -> {
                    text.append(event.text)
                    emit(AgentLoopEvent.TextDelta(event.text))
                }
                is AgentStreamEvent.ThinkingDelta -> {
                    reasoning.append(event.text)
                    emit(AgentLoopEvent.ThinkingDelta(event.text))
                }
                is AgentStreamEvent.Reasoning -> reasoning = StringBuilder(event.text)
                is AgentStreamEvent.ToolCallComplete -> entries += AgentTurnResult.ToolEntry(
                    id = event.id, name = event.name,
                    input = event.input, rawInput = event.input.jsonString(),
                )
                is AgentStreamEvent.Usage -> usage = event.usage
                is AgentStreamEvent.Done -> stopReason = event.stopReason
                is AgentStreamEvent.BlockStart, is AgentStreamEvent.ToolInputDelta -> Unit
            }
        }

        return AgentTurnResult(
            text = text.toString(),
            toolEntries = entries,
            reasoning = reasoning.toString().ifEmpty { null },
            stopReason = stopReason,
            usage = usage,
            isInterrupted = stopReason == null,
        )
    }

    // MARK: - 上下文治理

    /**
     * 图片老化：只保留最近 N 张，更早的换成文字占位。
     *
     * 图片是上下文里最重的东西（一张约 800 token），而对话进行到后面
     * 早期的图片几乎都不再需要 —— 模型已经把它描述过一遍了。
     */
    private fun trimOldImages() {
        var remaining = KEEP_IMAGES
        for (messageIndex in history.indices.reversed()) {
            val msg = history[messageIndex]
            if (msg.parts.none { it is AgentContentPart.Image }) continue
            val newParts = msg.parts.reversed().map { part ->
                if (part !is AgentContentPart.Image) return@map part
                if (remaining > 0) {
                    remaining--
                    part
                } else {
                    AgentContentPart.Text("（一张较早发送的图片已从上下文移出，如需重看请让用户再发一次）")
                }
            }.reversed()
            history[messageIndex] = msg.copy(parts = newParts)
        }
    }

    /**
     * 压缩历史：保留首尾，中间交给模型总结成一段。
     *
     * 首尾都留是有讲究的 —— 开头几条带着任务起点，结尾几条是正在做的事，
     * 两头都压掉模型会不知道自己在干嘛。
     *
     * 摘要生成失败就退化成占位说明：压缩失败不能让循环卡死，
     * 丢内容也好过丢可用性（占位里明确要求「重新查询而不是凭印象作答」）。
     */
    private suspend fun compactHistory() {
        if (history.size <= 6) return
        val head = history.take(2)
        val tail = history.takeLast(4)
        val middle = history.drop(2).dropLast(4)
        if (middle.isEmpty()) return

        val marker = summarize?.let { generate ->
            val transcript = middle.joinToString("\n") { message ->
                val who = if (message.role == AgentMessage.Role.USER) "用户" else "助手"
                val tools = message.toolUses.map { it.name }
                val suffix = if (tools.isEmpty()) "" else "（调用了 ${tools.joinToString("、")}）"
                "$who：${message.plainText}$suffix"
            }
            runCatching { generate(transcript) }
                .onFailure { Log.w(TAG, "压缩失败，退化为占位", it) }
                .getOrNull()
                ?.let { AgentMessage.user("（以下是这段对话较早部分的摘要，作为背景参考）\n$it") }
        } ?: placeholder(middle.size)

        history.clear()
        history += head
        history += marker
        history += tail
        lastContextTokens = 0   // 折叠后旧基线失效，下轮重新估算
        Log.i(TAG, "历史已折叠 dropped=${middle.size}")
    }

    private fun placeholder(dropped: Int) = AgentMessage.user(
        "（中间 $dropped 条较早的对话已折叠以节省上下文。" +
            "如需其中的具体信息，请重新查询而不是凭印象作答。）"
    )
}
