package com.chunland.app.core.ai

/**
 * 发送期历史裁剪（对齐 iOS AIOrchestrator.wireHistory）。
 * 输入是不含 system 的对话历史（buildWire 的 system 由调用方现拼后另加）；
 * 两个变换只作用于发出去的请求 —— UI 与本地持久化始终保留完整原文：
 *
 * 1. 轮次裁剪：只保留最近 [maxUserTurns] 个用户轮。在用户消息边界整轮切，
 *    assistant 的 tool_calls 与其 tool 结果天然成对保留/成对丢弃（拦腰截断会破 wire 协议）；
 *    更早的折叠成一句 assistant 占位。
 * 2. 过期工具结果折叠：最后一个用户消息之前的实时类工具结果（[AiToolName.resultVolatile]：
 *    购物车/订单/接单/店铺快照）替换为过期占位 —— 物理杜绝模型复用过期数据
 *    （比 prompt 规则 1 的许愿硬），token 同时大降。搜索/详情/分类结果刻意保留（规则 3 允许复用）。
 */
internal fun foldWireHistory(
    history: List<ChatWireMessage>,
    maxUserTurns: Int = 8,
): List<ChatWireMessage> {
    var msgs = history
    val userIdxs = msgs.indices.filter { msgs[it].role == "user" }
    if (userIdxs.size > maxUserTurns) {
        val cut = userIdxs[userIdxs.size - maxUserTurns]
        msgs = listOf(ChatWireMessage("assistant", "（更早的 $cut 条消息已折叠以节省上下文）")) +
            msgs.subList(cut, msgs.size)
    }
    val lastUser = msgs.indexOfLast { it.role == "user" }
    if (lastUser < 0) return msgs
    return msgs.mapIndexed { i, m ->
        val volatileTool = m.role == "tool" &&
            m.name?.let { AiToolName.fromWire(it)?.resultVolatile } == true
        if (i < lastUser && volatileTool) {
            m.copy(content = "（此工具结果已过期折叠；需要最新数据请重新调用 ${m.name}）")
        } else {
            m
        }
    }
}
