package com.chunland.app.core.ai.tools

import android.util.Log
import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.AiToolScope
import com.chunland.app.core.ai.domain.AgentCard
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
    /** 页面上下文里天然合法的 id（商品详情页的 code、订单页的 id） */
    seedProvenance: Map<AiProvenanceKind, List<String>> = emptyMap(),
    /** 当前活跃身份。用闭包而不是快照：身份可能在会话存续期间被切换 */
    private val activeIdentity: () -> String,
) : AgentToolExecuting {

    /** 该会话见过的 id。只读工具登记，变更工具受它约束 */
    private val provenance = ProvenanceRecorder(seedProvenance)

    /** 服务端工具体的调用侧（R5） */
    private val remote = AiToolRemote(graph)

    /** 线上下发的只读工具（schema 不在端上源码里） */
    private val wireCatalog = graph.aiWireToolCatalog

    /** 本轮攒下的结构化卡片（R3），由循环在批次执行后取走 */
    private val pendingCards = mutableListOf<AgentCard>()

    override suspend fun drainCards(): List<AgentCard> {
        val out = pendingCards.toList()
        pendingCards.clear()
        return out
    }

    /** 工具执行上下文 —— 作用域 + provenance，随会话不变 */
    private val toolContext = AiToolContext(scope, provenance)

    /** 全部已注册工具。新增域 = 在此追加该域的 specs（仅此一行变更） */
    private val specs: List<AgentToolSpec> = agentShoppingTools(graph) + agentBusinessTools(graph)

    private fun spec(name: String): AgentToolSpec? = specs.firstOrNull { it.name.wire == name }

    /**
     * 线上下发的工具。
     *
     * **钉死的优先**：名字撞上枚举里已有的，一律当钉死的处理，下发的那份丢弃。
     * 防的是服务端下发一个叫 `place_order` 的「只读」工具来遮蔽真的那个。
     */
    private suspend fun wireSpec(name: String): AgentRemoteToolSpec? {
        if (AiToolName.fromWire(name) != null) return null
        return wireCatalog.tools(activeIdentity()).firstOrNull { it.name == name }
    }

    /**
     * 下发给模型的工具集 = 身份可用集 ∩ 页面建议集。
     *
     * 取交集而不是并集：页面与身份本就同层布局，交集通常是无损的，
     * 但守住「换身份后残留的页面上下文越权」这条缝。
     */
    override suspend fun availableTools(): List<AgentToolDefinition> {
        val identity = activeIdentity()
        val pinned = specs
            .filter { it.name.allowedFor(identity) }
            .filter { suggested == null || it.name in suggested }
            .map { it.definition }

        // 页面 ✨ 有建议集时**不加下发工具**：那个子集是页面刻意收窄的
        // （「在这个页面只该做这几件事」），而页面根本不知道有哪些下发工具，
        // 塞进去等于替页面做了它没做的决定。tab 主对话（无建议集）才给全。
        if (suggested != null) return pinned

        return mergeWireTools(
            pinned = pinned,
            pinnedNames = AiToolName.entries.map { it.wire }.toSet(),
            wire = wireCatalog.tools(identity),
            identity = identity,
            onCollision = { Log.e(TAG, "下发工具与钉死的重名，已丢弃：$it") },
        )
    }

    override suspend fun exists(name: String): Boolean =
        spec(name) != null || wireSpec(name) != null

    override suspend fun isAvailable(name: String): Boolean {
        spec(name)?.let { return it.name.allowedFor(activeIdentity()) }
        return wireSpec(name)?.allowedFor(activeIdentity()) == true
    }

    /**
     * 不可用时给模型的说明。
     *
     * 必须写清「需要什么身份」「去哪切换」「不要重试」—— 只说「不可用」
     * 模型会当成偶发失败反复重试，撞满整个轮次预算。
     */
    override suspend fun unavailableMessage(name: String): String {
        val spec = spec(name)
            ?: run {
                val wire = wireSpec(name)
                    ?: return "工具 $name 不存在。请从当前可用的工具中选择。"
                val ids = wire.identities.map(AiToolName::identityLabel).sorted().joinToString("或")
                return "工具 $name 在当前身份（${AiToolName.identityLabel(activeIdentity())}）下不可用，" +
                    "此操作需要${ids}身份。请直接告知用户：到「我的」页切换身份后再试，不要重试本工具。"
            }
        val identity = activeIdentity()
        val needed = spec.name.allowedIdentities
            .map(AiToolName::identityLabel)
            .sorted()
            .joinToString("或")
        return "工具 $name 在当前身份（${AiToolName.identityLabel(identity)}）下不可用，" +
            "此操作需要${needed}身份。请直接告知用户：到「我的」页切换身份后再试，不要重试本工具。"
    }

    /** 下发工具**恒走服务端**（它本来就没有本地实现）。 */
    override suspend fun isRemote(name: String): Boolean {
        spec(name)?.let { return it.remote }
        return wireSpec(name) != null
    }

    /**
     * 下发工具**恒为只读**。
     *
     * 不是「查一个字段」——[AgentRemoteToolSpec] 结构上就没有 kind，
     * 这里对下发工具返回 false 是唯一可能的结果。
     * HITL 确认框因此不可能被下发内容绕过。
     */
    override suspend fun isMutation(name: String): Boolean =
        spec(name)?.kind == AiToolName.Kind.MUTATION

    override suspend fun prepare(name: String, input: AgentToolInput): AgentPreparedMutation {
        val spec = spec(name)
            ?: return AgentPreparedMutation.Abort("工具 $name 不存在。")

        // 执行期解析也在服务端的：拿票据与意图，确认后用票据 commit。
        // 快照在服务端，端上改不了将要执行的内容。
        if (spec.remotePrepare) {
            return when (val r = remote.prepare(name, input, activeIdentity(), scope)) {
                is AiToolRemote.RemotePrepared.Abort -> AgentPreparedMutation.Abort(r.text)
                is AiToolRemote.RemotePrepared.Ready -> AgentPreparedMutation.Ready(
                    intent = AgentMutationIntent(
                        id = UUID.randomUUID().toString(),
                        toolName = name,
                        summary = r.summary,
                        details = r.details,
                    ),
                ) {
                    val result = remote.commit(r.token)
                    for ((kind, values) in result.ids) provenance.record(kind, values)
                    pendingCards += result.cards
                    result.text
                }
            }
        }
        // 有本地 prepare 的走本地执行期解析；
        // 没有的用 intentSummary 生成摘要，执行时才跑 run。
        spec.prepare?.let { return it(input, toolContext) }

        return AgentPreparedMutation.Ready(
            intent = AgentMutationIntent(
                id = UUID.randomUUID().toString(),
                toolName = name,
                summary = spec.intentSummary?.invoke(input) ?: "执行 $name",
                details = displayDetails(input),
            ),
        // 走 execute 而不是直接 spec.run —— 那里才有远端分流。
        // **摘要仍由端上的 intentSummary 生成**：确认框是 UI 语义，
        // 而且摘要只是参数的人话化，没有取数，不值得多一次往返。
        ) { execute(name, input) }
    }

    // MARK: - provenance 守卫
    //
    // **只约束变更工具**。只读工具是模型「去看一眼」的手段，约束它等于让模型
    // 无从获得任何 id —— 用户手打一个商品代码问「这个多少钱」也会被挡。
    //
    // 每条拒绝话术都必须做到两件事：说清**先调哪个只读工具**、明确**禁止原样重试**。
    // 只说「不认识这个 id」，模型会理解成偶发失败反复重试，撞满整个轮次预算。
    override suspend fun provenanceRejection(name: String, input: AgentToolInput): String? {
        return when (AiToolName.fromWire(name)) {

            AiToolName.ADD_TO_CART -> {
                val code = input.string("product_code").orEmpty()
                // 缺参不归这里管 —— preflight 会报，两处都报会让模型收到两种说法
                if (code.isEmpty() || provenance.has(AiProvenanceKind.PRODUCT, code)) null
                else "商品代码 $code 不在本次对话出现过的商品里，很可能记串了或是编的。" +
                    "请先用 search_products 搜到它、或用 get_product_detail 确认它存在，" +
                    "拿到确切代码后再加购。不要原样重试本次调用。"
            }

            AiToolName.ASSIGN_CATEGORY_PRODUCTS -> {
                val categoryId = input.int("category_id")
                if (categoryId != null && !provenance.has(AiProvenanceKind.CATEGORY, categoryId.toString())) {
                    "分类 id $categoryId 不在本次对话出现过的分类里。" +
                        "请先用 list_category_schemes 拿到本店各分类的 category_id 再归类。" +
                        "不要原样重试本次调用。"
                } else {
                    val codes = AgentSchemeInput.codes(input.string("product_codes").orEmpty())
                    val unseen = provenance.unseen(AiProvenanceKind.PRODUCT, codes)
                    if (unseen.isEmpty()) {
                        null
                    } else {
                        val listed = unseen.take(10).joinToString("、")
                        "这些商品代码本次对话没出现过：$listed" +
                        (if (unseen.size > 10) " 等 ${unseen.size} 个" else "") +
                        "。归类只能用 list_store_products 返回过的 code —— 请先调它拿到本店商品清单，" +
                        "只归其中出现过的商品。不要原样重试本次调用。"
                    }
                }
            }

            AiToolName.PROPOSE_ADJUSTMENT -> {
                val orderId = input.int("order_id")
                val itemId = input.int("order_item_id")
                if (orderId != null && !provenance.has(AiProvenanceKind.ORDER, orderId.toString())) {
                    "订单 id $orderId 不在本次对话出现过的订单里。" +
                        "请先用 list_my_claims 查你的接单列表拿到确切的订单 id。不要原样重试本次调用。"
                } else if (itemId != null && !provenance.has(AiProvenanceKind.ORDER_ITEM, itemId.toString())) {
                    "商品条目 id $itemId 不在本次对话出现过的条目里。" +
                        "请先用 get_order_detail 查这笔订单，它会列出每个商品条目的 id。" +
                        "不要原样重试本次调用。"
                } else null
            }

            else -> null
        }
    }

    override suspend fun execute(name: String, input: AgentToolInput): String {
        val spec = spec(name)
            ?: run {
                // 下发工具：没有本地实现，只能走服务端
                if (wireSpec(name) != null) return callRemote(name, input)
                return "工具 $name 不存在。请从当前可用的工具中选择。"
            }
        // 已搬到服务端的：打端点、登记服务端告知的 id、原样返回它给的文本。
        // **provenance 仍在端上** —— 服务端不持有 AI 会话，只负责告知结果里有哪些 id。
        // 漏登记的后果是静默的：模型看得到 id，下一步用它却被自己拦下。
        if (spec.remote) {
            return callRemote(name, input)
        }
        return spec.run(input, toolContext)
    }

    /**
     * 打服务端工具体，登记它告知的 id，攒下卡片。钉死与下发两类共用。
     *
     * **provenance 仍在端上** —— 服务端不持有 AI 会话，只负责告知结果里有哪些 id。
     * 漏登记的后果是静默的：模型看得到 id，下一步用它却被自己拦下。
     */
    private suspend fun callRemote(name: String, input: AgentToolInput): String {
        val result = remote.call(name, input, activeIdentity(), scope)
        for ((kind, values) in result.ids) provenance.record(kind, values)
        pendingCards += result.cards
        return result.text
    }

    companion object {
        private const val TAG = "AgentToolRegistry"

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
