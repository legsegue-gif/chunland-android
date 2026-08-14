package com.chunland.app.core.ai.loop

import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.domain.AgentToolInput

/**
 * 循环与业务工具之间的接缝（对齐 iOS AgentToolExecuting.swift）。
 *
 * 循环层不知道有哪些工具、怎么执行、谁能用 —— 它只认这个接口。
 * 业务侧（工具注册表）实现它，于是「加一个工具」不需要动循环一行代码。
 */

/** 一次变更操作的意图 —— 给用户看的确认内容 */
data class AgentMutationIntent(
    val id: String,
    /** 工具名（内部标识） */
    val toolName: String,
    /** 一句话说明要做什么（「加入购物车：坚果礼盒 ×2」） */
    val summary: String,
    /** 关键参数的展示形式（金额、数量、地址等） */
    val details: Map<String, String> = emptyMap(),
)

/**
 * 变更工具的执行期解析产物。
 *
 * **确认弹窗展示的内容与随后实际执行的动作必须来自同一份快照** ——
 * 这是「确认里看到的 = 实际执行的」由构造保证，而不是靠模型转述参数。
 * 地址、价格这类系统已有的数据由代码在执行期解析，绝不让模型重新收集。
 */
sealed interface AgentPreparedMutation {
    /** 前置条件不满足（地址簿为空、未达起送额）：不弹确认，文本直接作为工具结果回给模型 */
    data class Abort(val text: String) : AgentPreparedMutation

    /** 解析完成：intent 给用户确认，通过后执行 execute */
    data class Ready(
        val intent: AgentMutationIntent,
        val execute: suspend () -> String,
    ) : AgentPreparedMutation
}

/** 业务工具的执行接缝 */
interface AgentToolExecuting {
    /** 当前身份可用的工具定义（下发给模型的那一份） */
    suspend fun availableTools(): List<AgentToolDefinition>

    /** 工具是否存在（名字对不对） */
    suspend fun exists(name: String): Boolean

    /**
     * 工具在**当前活跃身份**下是否可用。
     *
     * 与 [availableTools] 分开是必需的：会话跨身份留存，模型会从历史里
     * 复调旧身份的工具 —— 「看不到」不等于「调不到」。
     */
    suspend fun isAvailable(name: String): Boolean

    /** 不可用时给模型的说明（要写清需要什么身份、去哪切换） */
    suspend fun unavailableMessage(name: String): String

    /** 是否是变更类工具（决定要不要走确认） */
    suspend fun isMutation(name: String): Boolean

    /** 变更类工具的执行期解析 */
    suspend fun prepare(name: String, input: AgentToolInput): AgentPreparedMutation

    /** 只读工具的执行 */
    suspend fun execute(name: String, input: AgentToolInput): String
}

/** 确认接缝 —— 由 UI 层实现 */
interface MutationConfirming {
    /**
     * 一次确认**一批**变更。返回 true = 全部执行，false = 全部取消。
     *
     * 批量而不是逐个：放开轮次后一个任务可能产生 5-10 个变更
     * （商家归类每个分类调一次），逐个弹窗用户会疯。
     * 批量不削弱安全性 —— 用户仍看到每一项的完整摘要，只是把 N 次点击压成 1 次。
     */
    suspend fun confirm(batch: List<AgentMutationIntent>): Boolean
}
