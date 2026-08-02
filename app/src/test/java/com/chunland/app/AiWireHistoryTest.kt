package com.chunland.app

import com.chunland.app.core.ai.ChatWireMessage
import com.chunland.app.core.ai.WireToolCall
import com.chunland.app.core.ai.WireToolCallFunction
import com.chunland.app.core.ai.foldWireHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁发送期历史裁剪契约（foldWireHistory，对齐 iOS AIOrchestrator.wireHistory）：
 * 1. 过期折叠 —— 最后一个 user 之前的实时类工具结果（resultVolatile）被替换为占位，
 *    当前轮（最后 user 之后）的结果原样保留；
 * 2. 可复用结果（search_products 等）不折叠 —— prompt 规则 3 允许复用；
 * 3. 轮次裁剪 —— 超过 maxUserTurns 在 user 边界整轮切 + 折叠占位打头，
 *    assistant.tool_calls 与其 tool 结果成对丢弃（绝不拦腰截断破 wire 协议）。
 */
class AiWireHistoryTest {

    private fun user(t: String) = ChatWireMessage("user", t)
    private fun assistant(t: String) = ChatWireMessage("assistant", t)
    private fun toolCallRound(tool: String): Pair<ChatWireMessage, ChatWireMessage> {
        val call = WireToolCall(id = "call_$tool", function = WireToolCallFunction(tool, "{}"))
        return ChatWireMessage("assistant", null, toolCalls = listOf(call)) to
            ChatWireMessage("tool", "$tool 的原始结果", toolCallId = "call_$tool", name = tool)
    }

    @Test
    fun `volatile tool results before last user are folded, current round kept`() {
        val (a1, t1) = toolCallRound("get_cart")
        val (a2, t2) = toolCallRound("get_cart")
        val history = listOf(
            user("购物车有什么"), a1, t1, assistant("有冰箱"),
            user("再看一次"), a2, t2,
        )
        val out = foldWireHistory(history)
        assertEquals(history.size, out.size)
        assertTrue(out[2].content!!.contains("已过期折叠"))          // 旧轮 get_cart 折叠
        assertEquals("get_cart 的原始结果", out[6].content)          // 当前轮原样保留
        assertEquals(listOf("call_get_cart"), out[5].toolCalls!!.map { it.id })  // 配对结构不动
    }

    @Test
    fun `reusable results are never folded`() {
        val (a1, t1) = toolCallRound("search_products")
        val history = listOf(user("有蚊香吗"), a1, t1, assistant("有一款"), user("加购"))
        val out = foldWireHistory(history)
        assertEquals("search_products 的原始结果", out[2].content)
    }

    @Test
    fun `history is trimmed at user boundary with fold note, pairs dropped together`() {
        val (a0, t0) = toolCallRound("get_cart")
        val old = listOf(user("旧轮"), a0, t0, assistant("旧回答"))
        val recent = (1..8).flatMap { listOf(user("问 $it"), assistant("答 $it")) }
        val out = foldWireHistory(old + recent, maxUserTurns = 8)

        assertEquals(1 + recent.size, out.size)
        assertTrue(out[0].content!!.contains("已折叠"))
        assertEquals("问 1", out[1].content)
        // 旧轮的 tool_calls/tool 成对消失：留存历史里不许出现任何残缺配对
        assertTrue(out.none { it.role == "tool" })
        assertTrue(out.none { it.toolCalls != null })
    }

    @Test
    fun `history without user messages passes through`() {
        val history = listOf(assistant("独白"))
        assertEquals(history, foldWireHistory(history))
    }
}
