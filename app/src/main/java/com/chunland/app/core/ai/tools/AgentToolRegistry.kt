package com.chunland.app.core.ai.tools

import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.AiToolScope
import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.domain.AgentToolInput
import com.chunland.app.core.ai.loop.AgentMutationIntent
import com.chunland.app.core.ai.loop.AgentPreparedMutation
import com.chunland.app.core.ai.loop.AgentToolExecuting
import java.util.UUID

/**
 * 工具注册表（循环与业务之间的实现，对齐 iOS AgentToolRegistry.swift）。
 *
 * 实现 [AgentToolExecuting] —— 循环层只认那个接口，不知道有哪些工具。
 *
 * **三身份可用集是这里的核心不变量**：
 * 工具可用集是「当前活跃身份」的函数，两侧共用同一判定 ——
 * - 下发侧：[availableTools] 裁剪发给模型的 schema
 * - 执行侧：[isAvailable] 在管道的 preflight 阶段再拦一次
 *
 * 执行侧必须存在：会话跨身份留存（切身份不清历史），模型可能从历史里
 * 复调旧身份的工具名，「模型看不到」不等于「调不到」。
 */
class AgentToolRegistry(
    graph: AppGraph,
    /** 该会话的作用域 —— 进店等场景据此把查询硬限定到某个商家 */
    private val scope: AiToolScope,
    /** 页面建议的工具子集（AiContext.tools）。null = 该身份的全量 */
    private val suggested: Set<AiToolName>?,
    /** 当前活跃身份。用闭包而不是快照：身份可能在会话存续期间被切换 */
    private val activeIdentity: () -> String,
) : AgentToolExecuting {

    /** 全部已注册工具。新增域 = 在此追加该域的 specs（仅此一行变更） */
    private val specs: List<AgentToolSpec> = agentShoppingTools(graph) + agentBusinessTools(graph)

    private fun spec(name: String): AgentToolSpec? = specs.firstOrNull { it.name.wire == name }

    /**
     * 下发给模型的工具集 = 身份可用集 ∩ 页面建议集。
     *
     * 取交集而不是并集：页面与身份本就同层布局，交集通常是无损的，
     * 但守住「换身份后残留的页面上下文越权」这条缝。
     */
    override suspend fun availableTools(): List<AgentToolDefinition> {
        val identity = activeIdentity()
        return specs
            .filter { it.name.allowedFor(identity) }
            .filter { suggested == null || it.name in suggested }
            .map { it.definition }
    }

    override suspend fun exists(name: String): Boolean = spec(name) != null

    override suspend fun isAvailable(name: String): Boolean =
        spec(name)?.name?.allowedFor(activeIdentity()) == true

    /**
     * 不可用时给模型的说明。
     *
     * 必须写清「需要什么身份」「去哪切换」「不要重试」—— 只说「不可用」
     * 模型会当成偶发失败反复重试，撞满整个轮次预算。
     */
    override suspend fun unavailableMessage(name: String): String {
        val spec = spec(name)
            ?: return "工具 $name 不存在。请从当前可用的工具中选择。"
        val identity = activeIdentity()
        val needed = spec.name.allowedIdentities
            .map(AiToolName::identityLabel)
            .sorted()
            .joinToString("或")
        return "工具 $name 在当前身份（${AiToolName.identityLabel(identity)}）下不可用，" +
            "此操作需要${needed}身份。请直接告知用户：到「我的」页切换身份后再试，不要重试本工具。"
    }

    override suspend fun isMutation(name: String): Boolean =
        spec(name)?.kind == AiToolName.Kind.MUTATION

    override suspend fun prepare(name: String, input: AgentToolInput): AgentPreparedMutation {
        val spec = spec(name)
            ?: return AgentPreparedMutation.Abort("工具 $name 不存在。")

        // 有 prepare 的走执行期解析（确认与执行共用同一份快照）；
        // 没有的用 intentSummary 生成摘要，执行时才跑 run。
        spec.prepare?.let { return it(input, scope) }

        return AgentPreparedMutation.Ready(
            intent = AgentMutationIntent(
                id = UUID.randomUUID().toString(),
                toolName = name,
                summary = spec.intentSummary?.invoke(input) ?: "执行 $name",
                details = displayDetails(input),
            ),
        ) { spec.run(input, scope) }
    }

    override suspend fun execute(name: String, input: AgentToolInput): String {
        val spec = spec(name)
            ?: return "工具 $name 不存在。请从当前可用的工具中选择。"
        return spec.run(input, scope)
    }

    companion object {
        /**
         * 把工具参数转成给用户看的键值对（确认框展示）。
         *
         * 跳过 tool_title —— 它是模型写给用户看的说明，已经在摘要里了，
         * 再作为一个参数列出来是重复。
         */
        fun displayDetails(input: AgentToolInput): Map<String, String> =
            input.keys
                .filter { it != AgentToolDefinition.TOOL_TITLE_KEY && !input.isBlank(it) }
                .associateWith { input.string(it) ?: "" }
    }
}
