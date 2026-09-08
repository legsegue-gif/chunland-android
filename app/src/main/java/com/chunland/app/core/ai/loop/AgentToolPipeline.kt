package com.chunland.app.core.ai.loop

import android.util.Log
import com.chunland.app.core.ai.domain.AgentContentPart
import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.domain.AgentToolInput
import com.chunland.app.core.ai.domain.AgentToolPreflight
import com.chunland.app.core.ai.domain.AgentTurnResult
import com.chunland.app.core.ai.prompt.AiFence
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 工具执行管道（对齐 iOS AgentToolPipeline.swift）。
 *
 * 单个工具调用的固定处理顺序：
 * ```
 * 取消预检 → 循环检测 → 参数修复 → preflight（身份守卫 + 必填校验 + provenance）→ 执行 → 记录
 * ```
 *
 * **任何一条路径都不能跳过最后的记录步骤** —— 漏记会让循环检测器看不到打转，
 * 于是熔断永远不触发，放开的轮次上限就成了隐患而不是能力。
 * 所以每条 return 路径都产出同一种结果对象，由本管道统一 record。
 */
class AgentToolPipeline(
    private val executor: AgentToolExecuting,
    private val confirmer: MutationConfirming,
    private val detector: ToolLoopDetector,
) {

    companion object {
        private const val TAG = "AiToolPipeline"

        /**
         * 只读工具的并发上限。
         * 工具全是自家服务端调用，5 路足够；实际同轮很少超过 3 个。
         */
        const val MAX_CONCURRENT = 5
    }

    data class Outcome(
        val toolId: String,
        val toolName: String,
        /** 回给模型的工具结果片段 */
        val part: AgentContentPart,
        /** 是否因用户取消而中止 */
        val cancelled: Boolean,
        /** 模型自述的这次调用在做什么（tool_title），给 UI 展示 */
        val title: String?,
        val isError: Boolean,
    )

    /**
     * 执行一批工具调用，结果**严格按原顺序**返回。
     *
     * 顺序是硬约束：wire 上 `role:"tool"` 帧必须与前一条 assistant 的
     * `tool_calls` 同序，错位会让模型把 A 的结果当成 B 的。
     */
    suspend fun executeBatch(
        entries: List<AgentTurnResult.ToolEntry>,
        tools: List<AgentToolDefinition>,
    ): List<Outcome> {
        if (entries.isEmpty()) return emptyList()

        // 先把变更类挑出来做执行期解析，凑成一个批次一次确认
        val prepared = mutableMapOf<Int, AgentPreparedMutation>()
        val intents = mutableListOf<AgentMutationIntent>()

        entries.forEachIndexed { index, entry ->
            if (!executor.isMutation(entry.name)) return@forEachIndexed
            // 不可用的工具不必解析 —— 但这里**只判定不记账**：真正的守卫与
            // 循环记账唯一发生在 runOne 的 preCheck。两处都记会让同一次调用
            // 被数两遍，「连续 N 次」的阈值语义就变成了 N/2 轮。
            if (!executor.isAvailable(entry.name)) return@forEachIndexed
            val repaired = repairInput(entry, tools)
            // provenance 不过的同样不必解析，且同样**只判定不记账**。
            // 不跳过的话用户会先看到一个确认弹窗、点了确认才被告知「这个 id 没见过」。
            if (executor.provenanceRejection(entry.name, repaired) != null) return@forEachIndexed
            runCatching { executor.prepare(entry.name, repaired) }
                .onSuccess { result ->
                    prepared[index] = result
                    if (result is AgentPreparedMutation.Ready) intents += result.intent
                }
                .onFailure { Log.w(TAG, "变更解析失败 tool=${entry.name}") }
        }

        // 一次确认整批。用户取消 = 整批取消
        var approved = true
        if (intents.isNotEmpty()) {
            approved = confirmer.confirm(intents)
            Log.i(TAG, "批量确认 count=${intents.size} approved=$approved")
        }

        val outcomes = arrayOfNulls<Outcome>(entries.size)
        val isMutation = entries.map { executor.isMutation(it.name) }

        // 只读工具并发（无写副作用、同轮之间也没有数据依赖 ——
        // 依赖前一个结果的调用模型只能在下一轮才发得出）
        coroutineScope {
            val gate = Semaphore(MAX_CONCURRENT)
            entries.indices.filter { !isMutation[it] }.map { index ->
                async {
                    gate.withPermit {
                        index to runOne(entries[index], tools, null, true)
                    }
                }
            }.awaitAll().forEach { (index, outcome) -> outcomes[index] = outcome }
        }

        // 变更工具按序（同轮「先加购后查车」的因果不能乱）
        entries.indices.filter { isMutation[it] }.forEach { index ->
            outcomes[index] = runOne(entries[index], tools, prepared[index], approved)
        }

        return entries.mapIndexed { index, entry ->
            outcomes[index] ?: Outcome(
                toolId = entry.id,
                toolName = entry.name,
                part = AgentContentPart.ToolResult(entry.id, entry.name, "工具未能执行。", true),
                cancelled = false, title = null, isError = true,
            )
        }
    }

    // MARK: - 单个执行

    private suspend fun runOne(
        entry: AgentTurnResult.ToolEntry,
        tools: List<AgentToolDefinition>,
        prepared: AgentPreparedMutation?,
        approved: Boolean,
    ): Outcome {
        val title = entry.input.string(AgentToolDefinition.TOOL_TITLE_KEY)

        // ① 取消预检 —— 用户点了停止之后才轮到的调用，直接短路，
        //    但仍要产出结果片段，否则历史里会留下没有结果的调用（孤儿）
        if (!currentCoroutineContext().isActive) {
            return finish(entry, title, "用户已取消操作。", isError = true, cancelled = true)
        }

        // ② 循环检测 + ③ 身份守卫 + ④ 未知工具
        return when (val check = preCheck(entry)) {
            is PreCheck.Blocked -> finish(entry, title, check.message, isError = true, cancelled = false)
            is PreCheck.WarningAttached ->
                execute(entry, tools, prepared, approved, title, check.note)
            is PreCheck.Pass ->
                execute(entry, tools, prepared, approved, title, null)
        }
    }

    private sealed interface PreCheck {
        data object Pass : PreCheck
        data class Blocked(val message: String) : PreCheck
        data class WarningAttached(val note: String) : PreCheck
    }

    private suspend fun preCheck(entry: AgentTurnResult.ToolEntry): PreCheck {
        // 先定这次调用属于哪一类（存在？当前身份可用？）——
        // 必须在 detector.check 之前拿到：被熔断阻断的那次同样要按**原本的类别**
        // 记账。若记成普通调用，连击链会被自己打断，于是「不可用工具连击」
        // 这类低阈值保护永远数不到阈值，只能等全局熔断（阈值高得多）兜底。
        val exists = executor.exists(entry.name)
        val available = exists && executor.isAvailable(entry.name)

        // 循环检测放在最前：模型已经在打转时，连参数修复都不必做了
        when (val level = detector.check(entry.name, entry.input)) {
            is LoopLevel.Blocked -> {
                detector.record(
                    entry.name, entry.input, null,
                    unavailable = exists && !available, unknown = !exists,
                )
                return PreCheck.Blocked(level.message)
            }
            is LoopLevel.Warning -> return PreCheck.WarningAttached(level.message)
            LoopLevel.Pass -> Unit
        }

        if (!exists) {
            detector.record(entry.name, entry.input, null, unknown = true)
            return PreCheck.Blocked("工具「${entry.name}」不存在，请从可用工具中选择。")
        }

        // 身份守卫并入 preflight 阶段：这样它天然享受「拒绝话术禁止原样重试」
        // 的规范，也会被循环检测器记录 —— 模型反复撞同一堵墙会被熔断，
        // 而不是一轮轮撞满整个轮次预算。
        if (!available) {
            val message = executor.unavailableMessage(entry.name)
            detector.record(entry.name, entry.input, null, unavailable = true)
            return PreCheck.Blocked(message)
        }

        return PreCheck.Pass
    }

    private suspend fun execute(
        entry: AgentTurnResult.ToolEntry,
        tools: List<AgentToolDefinition>,
        prepared: AgentPreparedMutation?,
        approved: Boolean,
        title: String?,
        warning: String?,
    ): Outcome {
        val repaired = repairInput(entry, tools)

        // preflight：必填校验。拒绝话术必须明确禁止原样重试，
        // 否则模型收到「参数无效」会理解成「再发一次试试」
        val definition = tools.firstOrNull { it.name == entry.name }
        AgentToolPreflight.validate(entry.name, repaired, definition)?.let { rejection ->
            detector.record(entry.name, repaired, null)
            Log.w(TAG, "preflight 拒绝 tool=${entry.name} reason=${rejection.reason}")
            return finish(entry, title, rejection.modelMessage, isError = true, cancelled = false)
        }

        // provenance 守卫：本会话没见过的 id 一律拒绝。
        // 放在 preflight 之后 —— 必填都没齐时先报缺参，两道同时报会让模型收到两种说法。
        executor.provenanceRejection(entry.name, repaired)?.let { rejection ->
            detector.record(entry.name, repaired, null)
            Log.w(TAG, "provenance 拒绝 tool=${entry.name}")
            return finish(entry, title, rejection, isError = true, cancelled = false)
        }

        return try {
            val text = when (prepared) {
                is AgentPreparedMutation.Abort -> {
                    // 前置条件不满足 —— 不是错误，是要告诉模型「换个做法」
                    detector.record(entry.name, repaired, prepared.text)
                    return finish(entry, title, prepared.text, isError = false, cancelled = false)
                }
                is AgentPreparedMutation.Ready -> {
                    if (!approved) {
                        detector.record(entry.name, repaired, null)
                        return finish(entry, title, "用户取消了这次操作。",
                            isError = false, cancelled = true)
                    }
                    prepared.execute()
                }
                null -> executor.execute(entry.name, repaired)
            }

            detector.record(entry.name, repaired, text)
            // 服务端工具体的返回已消毒已围栏 —— 端上原样透传，绝不再过一遍
            val processing = if (executor.isRemote(entry.name)) {
                ResultProcessing.ALREADY_PROCESSED
            } else {
                ResultProcessing.FENCE_DATA
            }
            finish(entry, title, text, isError = false, cancelled = false,
                processing = processing, warning = warning)

        } catch (e: Throwable) {
            val message = "执行「${entry.name}」时出错：${e.message ?: e.javaClass.simpleName}"
            detector.record(entry.name, repaired, message)
            Log.e(TAG, "工具执行失败 tool=${entry.name}", e)
            finish(entry, title, message, isError = true, cancelled = false,
                processing = ResultProcessing.FENCE_DATA)
        }
    }

    // MARK: - 辅助

    private fun repairInput(
        entry: AgentTurnResult.ToolEntry,
        tools: List<AgentToolDefinition>,
    ): AgentToolInput {
        val definition = tools.firstOrNull { it.name == entry.name }
        val outcome = ToolArgsRepair.repair(entry.name, entry.input, entry.rawInput, definition)
        if (outcome.didRepair) {
            Log.i(TAG, "参数已修复 tool=${entry.name} repairs=${outcome.repairs.joinToString(",")}")
        }
        return outcome.input
    }

    /**
     * 工具结果文本要怎么处理。
     *
     * 三态而不是一个 Boolean：R5 之后结果有三种来源，处理方式互不相同，
     * 用布尔表达会出现「消毒了但没围栏」和「服务端已围栏又被端上消毒」两种错配。
     */
    private enum class ResultProcessing {
        /** 端上工具产出的数据：消毒 + 包数据围栏 */
        FENCE_DATA,
        /**
         * 管道自己的控制文案（阻断说明、前置条件引导）：只消毒不围栏 ——
         * 它们本身就是要模型照做的指令，包进「这是数据」的围栏等于自我否定。
         */
        CONTROL_TEXT,
        /**
         * 服务端工具体已消毒且已围栏：**原样透传**。
         * 再过一次消毒会把服务端加的围栏标记一并中和掉，围栏就白做了。
         */
        ALREADY_PROCESSED,
    }

    /**
     * 所有工具结果的**唯一出口** —— 消毒、围栏、追加系统提醒三步的顺序在这里由构造保证。
     *
     * 顺序不能动：先消毒（剥掉正文里伪造的标记），再围栏，最后才追加我们自己的
     * 系统提醒。反过来做等于把自己的提醒也消毒掉。
     *
     * @param warning 循环检测的非阻断警告，附在结果之后（围栏之外）。
     */
    private fun finish(
        entry: AgentTurnResult.ToolEntry,
        title: String?,
        text: String,
        isError: Boolean,
        cancelled: Boolean,
        processing: ResultProcessing = ResultProcessing.CONTROL_TEXT,
        warning: String? = null,
    ): Outcome {
        var body = when (processing) {
            ResultProcessing.FENCE_DATA -> AiFence.fence(AiFence.sanitize(text))
            ResultProcessing.CONTROL_TEXT -> AiFence.sanitize(text)
            ResultProcessing.ALREADY_PROCESSED -> text
        }
        if (warning != null) body += "\n\n" + AiFence.systemNote(warning)
        return Outcome(
            toolId = entry.id,
            toolName = entry.name,
            part = AgentContentPart.ToolResult(entry.id, entry.name, body, isError),
            cancelled = cancelled,
            title = title,
            isError = isError,
        )
    }
}
