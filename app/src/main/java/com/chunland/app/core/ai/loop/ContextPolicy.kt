package com.chunland.app.core.ai.loop

import com.chunland.app.core.ai.domain.AgentContentPart
import com.chunland.app.core.ai.domain.AgentMessage

/**
 * 上下文治理策略（对齐 iOS ContextPolicy.swift）。
 *
 * 放开轮次上限后的必需品：一个任务展开成十几轮工具调用，历史会迅速撑满窗口。
 * 旧实现只有「保留最近 8 个用户轮 + 实时结果折叠」这一招，粗暴且不看实际占用。
 *
 * 三级手段按代价从低到高：
 * - 卸载 —— 把大工具结果搬走换成引用。便宜、无损（可取回）
 * - 压缩 —— 让模型把旧对话总结成一段。贵、有损
 * - 耗尽 —— 两招都用尽还是满，只能让用户新开会话
 *
 * 阈值按模型窗口分档：小窗口模型压缩一次就没剩多少了，不如早点提示新开。
 */
class ContextPolicy(val contextWindow: Int) {

    /** 超过这个用量开始卸载。0 = 不启用 */
    val offloadThreshold: Int

    /** 卸载的目标用量（腾到这个水平就停手，不必全卸） */
    val offloadTarget: Int

    /** 超过这个用量触发压缩。0 = 不启用 */
    val compactThreshold: Int

    /** 该档位是否只提示新开会话（不自动压缩） */
    val exhaustedOnly: Boolean

    /** 是否允许用户手动压缩 */
    val manualCompactAllowed: Boolean

    init {
        when {
            contextWindow < 32_000 -> {
                // 太小：压缩本身就要占掉一大块，得不偿失。满了直接提示新开
                offloadThreshold = 0
                offloadTarget = 0
                compactThreshold = 0
                exhaustedOnly = true
                manualCompactAllowed = false
            }
            contextWindow < 64_000 -> {
                // 剩 10K 时卸载，不自动压缩（用户可手动）
                offloadThreshold = contextWindow - 10_000
                offloadTarget = contextWindow - 15_000
                compactThreshold = 0
                exhaustedOnly = true
                manualCompactAllowed = true
            }
            contextWindow < 128_000 -> {
                offloadThreshold = contextWindow - 20_000
                offloadTarget = contextWindow - 30_000
                compactThreshold = contextWindow - 10_000
                exhaustedOnly = false
                manualCompactAllowed = true
            }
            else -> {
                offloadThreshold = contextWindow - 40_000
                offloadTarget = contextWindow - 60_000
                compactThreshold = contextWindow - 20_000
                exhaustedOnly = false
                manualCompactAllowed = true
            }
        }
    }

    enum class Decision {
        OK,

        /** 该压缩了 */
        NEEDS_COMPACT,

        /** 压不动了 —— 提示用户新开会话 */
        EXHAUSTED,
    }

    fun decide(usedTokens: Int): Decision {
        if (compactThreshold > 0 && usedTokens >= compactThreshold) return Decision.NEEDS_COMPACT
        if (exhaustedOnly) {
            val ceiling = if (offloadThreshold > 0) offloadThreshold else (contextWindow * 0.9).toInt()
            if (usedTokens >= ceiling) return Decision.EXHAUSTED
        }
        return Decision.OK
    }

    fun shouldOffload(usedTokens: Int): Boolean =
        offloadThreshold > 0 && usedTokens >= offloadThreshold
}

/**
 * token 估算（对齐 iOS TokenEstimator）。
 *
 * 不打包分词表：一份 BPE 词表几 MB，而我们只需要回答「该不该压缩了」——
 * 这个决策容忍 ±15% 的误差。按字符类型加权估算零依赖、零体积，够用。
 *
 * 权重来自常见分词器的实际表现：
 * - CJK 表意文字 ≈ 1 token/字（有时 1.5，取 1 偏保守）
 * - 拉丁字母/数字 ≈ 1 token/4 字符
 * - 标点空白 ≈ 1 token/3 字符
 *
 * 一旦 API 返回了真实的 usage，就以它为准 —— 估算只在没有基线时兜底。
 */
object TokenEstimator {

    fun estimate(text: String): Int {
        var ideographs = 0
        var alphanumerics = 0
        var others = 0
        text.forEach { ch ->
            when {
                isIdeograph(ch) -> ideographs++
                ch.isLetterOrDigit() -> alphanumerics++
                else -> others++
            }
        }
        return ideographs + (alphanumerics + 3) / 4 + (others + 2) / 3
    }

    /**
     * 一条消息的估算量。
     *
     * 每条消息额外加 4 token 的固定开销 —— 角色标记、分隔符等 wire 层结构，
     * 在多轮长历史里这部分累积起来并不小。
     */
    fun estimate(message: AgentMessage): Int {
        var total = 4
        message.parts.forEach { part ->
            total += when (part) {
                is AgentContentPart.Text -> estimate(part.text)
                is AgentContentPart.ToolUse -> estimate(part.name) + estimate(part.input.jsonString())
                is AgentContentPart.ToolResult -> estimate(part.name) + estimate(part.text)
                // 图片的实际消耗随分辨率变化很大，取一个中等值。
                // 宁可高估 —— 低估会让上下文悄悄溢出，那是硬失败。
                // 不发送 → 不占上下文
                is AgentContentPart.Cards -> 0
                is AgentContentPart.Image -> 800
            }
        }
        message.reasoning?.let { total += estimate(it) }
        return total
    }

    fun estimate(history: List<AgentMessage>): Int = history.sumOf { estimate(it) }

    private fun isIdeograph(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x3040..0x30FF) || (code in 0x3400..0x4DBF) ||
            (code in 0x4E00..0x9FFF) || (code in 0xF900..0xFAFF) ||
            (code in 0xAC00..0xD7AF)
    }
}
