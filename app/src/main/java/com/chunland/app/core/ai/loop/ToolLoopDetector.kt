package com.chunland.app.core.ai.loop

import com.chunland.app.core.ai.domain.AgentToolInput
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具循环检测（对齐 iOS ToolLoopDetector.swift）。
 *
 * 放开轮次上限（8 → 30）的**前提条件**。没有它，一个打转的模型会把 30 轮全烧完，
 * 用户等半天拿到一句「轮次过多」。
 *
 * 四种打转形态各有不同的处置：
 * - 身份不可用工具连击 → 模型没听懂「切身份」的提示，再喊也没用，直接熔断
 * - 未知工具连击 → 工具名不存在，重试永远失败
 * - 相同调用无进展 → 同参数同结果反复出现，卡在一个点上
 * - 轮询无进展 → 反复查同一个状态，什么都没变
 *
 * 两级处置：
 * - 警告 —— 把提示**塞进工具结果**让模型自己纠正，不阻断（多数情况这就够了）
 * - 熔断 —— 直接返回阻断说明，不执行（模型已经证明自己纠正不了）
 */
data class ToolLoopConfig(
    val historySize: Int = 30,
    /**
     * 身份不可用工具连击几次熔断。
     *
     * 比其它阈值低得多：拒绝话术已经明确写了「到『我的』页切换身份后再试，
     * 不要重试本工具」，3 次仍在撞说明模型没听懂，再喊也没用。
     */
    val unavailableToolThreshold: Int = 3,
    val unknownToolThreshold: Int = 5,
    val repeatWarningThreshold: Int = 5,
    val pollWarningThreshold: Int = 5,
    val pollCriticalThreshold: Int = 10,
    val globalCircuitThreshold: Int = 15,
)

sealed interface LoopLevel {
    data object Pass : LoopLevel

    /** 提示塞进工具结果，仍然执行 */
    data class Warning(val message: String) : LoopLevel

    /** 阻断执行，把说明当作工具结果返回 */
    data class Blocked(val message: String) : LoopLevel
}

/**
 * 检测器。每个会话一个实例，随会话生命周期存活。
 */
class ToolLoopDetector(private val config: ToolLoopConfig = ToolLoopConfig()) {

    private data class Record(
        val toolName: String,
        val argsHash: String,
        val resultHash: String?,
        /** 这次调用是否因为工具不可用（身份不匹配）而被拒 */
        val unavailable: Boolean,
        /** 工具名是否根本不存在 */
        val unknown: Boolean,
    )

    private val history = ArrayDeque<Record>()
    /** 已发过的警告 key —— 同一个问题只提醒一次，反复喊会挤占上下文 */
    private val warnedKeys = mutableSetOf<String>()
    private val lock = Any()

    fun reset() = synchronized(lock) {
        history.clear()
        warnedKeys.clear()
    }

    // MARK: - 执行前

    /** 执行前检查。[LoopLevel.Blocked] 时调用方必须短路，把消息当作工具结果返回 */
    fun check(toolName: String, input: AgentToolInput): LoopLevel = synchronized(lock) {
        val hash = hash(toolName, input)

        // ① 身份不可用工具连击 —— 最高优先级
        val unavailableStreak = tailStreak { it.toolName == toolName && it.unavailable }
        if (unavailableStreak >= config.unavailableToolThreshold) {
            return LoopLevel.Blocked(
                "已连续 $unavailableStreak 次尝试当前身份不可用的工具「$toolName」。" +
                    "请停止重试，直接告诉用户需要切换身份，然后基于现有信息作答。"
            )
        }

        // ② 未知工具连击
        val unknownStreak = tailStreak { it.toolName == toolName && it.unknown }
        if (unknownStreak >= config.unknownToolThreshold) {
            return LoopLevel.Blocked(
                "工具「$toolName」不存在，已连续尝试 $unknownStreak 次。" +
                    "请从当前可用的工具列表中选择，或不使用工具直接作答。"
            )
        }

        // ③ 全局熔断：同工具同参数同结果反复出现
        val noProgress = noProgressStreak(toolName, hash)
        if (noProgress >= config.globalCircuitThreshold) {
            return LoopLevel.Blocked(
                "「$toolName」已用相同参数得到相同结果 $noProgress 次，没有任何进展。" +
                    "请停止调用，基于已有结果作答，或告诉用户这件事暂时做不到。"
            )
        }

        // ④ 轮询类：低阈值警告、高阈值熔断
        if (isPollingTool(toolName)) {
            if (noProgress >= config.pollCriticalThreshold) {
                return LoopLevel.Blocked(
                    "「$toolName」已查询 $noProgress 次且状态始终未变。" +
                        "请停止轮询，把当前状态告诉用户。"
                )
            }
            if (noProgress >= config.pollWarningThreshold) {
                return warnOnce(
                    "poll:$toolName:$hash",
                    "你已经查询「$toolName」$noProgress 次，状态没有变化。" +
                        "不要继续轮询 —— 把当前状态告诉用户，让他稍后再问。"
                )
            }
        }

        // ⑤ 相同参数重复（非轮询工具）
        val repeats = history.count { it.toolName == toolName && it.argsHash == hash }
        if (repeats >= config.repeatWarningThreshold) {
            return warnOnce(
                "repeat:$toolName:$hash",
                "你已用完全相同的参数调用「$toolName」$repeats 次。" +
                    "如果结果不是你想要的，请换参数或换个思路，不要重复同一次调用。"
            )
        }

        return LoopLevel.Pass
    }

    // MARK: - 执行后

    /**
     * 记录一次调用。**每条路径都必须调用**（包括被阻断、被拒绝、执行失败），
     * 漏记会让检测器看不到打转。
     */
    fun record(
        toolName: String,
        input: AgentToolInput,
        result: String?,
        unavailable: Boolean = false,
        unknown: Boolean = false,
    ) = synchronized(lock) {
        history.addLast(
            Record(
                toolName = toolName,
                argsHash = hash(toolName, input),
                resultHash = result?.let(::sha),
                unavailable = unavailable,
                unknown = unknown,
            )
        )
        while (history.size > config.historySize) history.removeFirst()
    }

    // MARK: - 内部

    /** 从尾部数连续满足条件的记录数 */
    private fun tailStreak(predicate: (Record) -> Boolean): Int {
        var n = 0
        for (record in history.reversed()) {
            if (!predicate(record)) break
            n++
        }
        // +1 是把「当前这次调用」算进去 —— check 发生在 record 之前
        return if (n > 0) n + 1 else 0
    }

    /**
     * 同工具同参数、且结果也相同的连续次数。
     *
     * 「结果相同」是关键：参数一样但结果在变，说明确实在推进（比如轮询到状态变了）。
     */
    private fun noProgressStreak(toolName: String, argsHash: String): Int {
        var n = 0
        var expected: String? = null
        var sawFirst = false
        for (record in history.reversed()) {
            if (record.toolName != toolName || record.argsHash != argsHash) break
            if (!sawFirst) {
                expected = record.resultHash
                sawFirst = true
            } else if (record.resultHash != expected) break
            n++
        }
        return if (n > 0) n + 1 else 0
    }

    private fun warnOnce(key: String, message: String): LoopLevel =
        if (warnedKeys.add(key)) LoopLevel.Warning(message) else LoopLevel.Pass

    companion object {
        /**
         * 哪些工具算「轮询」—— 反复查同一个状态。
         *
         * 用前缀判定而不是硬编码工具名清单：新增同类工具时不必改这里，
         * 而工具命名本来就有约定（查询类以 get_/list_ 开头）。
         */
        fun isPollingTool(name: String): Boolean =
            name.startsWith("get_") || name.startsWith("list_")

        fun hash(toolName: String, input: AgentToolInput): String =
            sha("$toolName|${canonical(input)}")

        /** 参数的规范化表示 —— 键排序后拼接，保证等价参数得到同一个哈希 */
        fun canonical(input: AgentToolInput): String =
            input.keys.sorted().joinToString("&") { key ->
                "$key=${input[key]?.let(::describe) ?: ""}"
            }

        private fun describe(value: kotlinx.serialization.json.JsonElement): String = when (value) {
            is JsonNull -> "null"
            is JsonPrimitive -> value.content
            is JsonArray -> "[" + value.joinToString(",") { describe(it) } + "]"
            is JsonObject -> "{" + value.keys.sorted()
                .joinToString(",") { "$it:${describe(value.getValue(it))}" } + "}"
        }

        fun sha(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
    }
}
