package com.chunland.app

import com.chunland.app.core.ai.domain.AgentContentPart
import com.chunland.app.core.ai.domain.AgentMessage
import com.chunland.app.core.ai.domain.AgentToolInput
import com.chunland.app.core.ai.prompt.AiFence
import com.chunland.app.core.ai.session.AiChatSession
import com.chunland.app.core.ai.session.ChatBlock
import com.chunland.app.core.ai.session.ChatDisplayMessage
import com.chunland.app.core.ai.session.ChatToolBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁「重开会话后工具块的终态与摘要」。
 *
 * 回归背景：曾出过一个 bug —— 重开会话后**每一个**历史工具调用都停在「执行中」
 * 转圈、且展不开结果。`displayFrom` 把 `ToolResult` part 直接丢了，`addTool` 建块
 * 默认 RUNNING，此后无人补状态；注释里提到用来补状态的 `mergeToolResults` 当时
 * 并不存在。编译与单测都不报，只有真的重开一次会话才看得见 ——
 * 所以这里必须有测试，否则下次照样静默退回去。
 */
class AiToolBlockTest {

    private fun toolUseMessage(id: String) = AgentMessage(
        role = AgentMessage.Role.ASSISTANT,
        parts = listOf(
            AgentContentPart.ToolUse(
                id = id,
                name = "search_products",
                input = AgentToolInput.parse("""{"tool_title":"搜索牛奶商品"}"""),
            ),
        ),
    )

    // 结果挂在 USER 消息上 —— domain 里只有 USER/ASSISTANT 两个角色，
    // wire 编码时才拆出独立的 role:"tool" 帧
    private fun toolResultMessage(id: String, text: String, isError: Boolean = false) = AgentMessage(
        role = AgentMessage.Role.USER,
        parts = listOf(
            AgentContentPart.ToolResult(id = id, name = "search_products", text = text, isError = isError),
        ),
    )

    private fun onlyToolBlock(displays: List<ChatDisplayMessage>): ChatToolBlock {
        val blocks = displays.flatMap { it.blocks }.filterIsInstance<ChatBlock.Tool>()
        assertEquals("应当只有一个工具块", 1, blocks.size)
        return blocks.first().block
    }

    // ---- 跨消息补状态（核心回归守卫）----

    @Test
    fun `重开会话后工具块是终态，不是永远转圈`() {
        val history = listOf(
            toolUseMessage("call_1"),
            toolResultMessage("call_1", AiFence.fence("找到 29 个商品")),
        )

        val block = onlyToolBlock(AiChatSession.displaysFrom(history))

        assertEquals(ChatToolBlock.Status.SUCCESS, block.status)
        assertEquals("找到 29 个商品", block.resultPreview)
    }

    @Test
    fun `失败的工具调用补成 FAILED`() {
        val history = listOf(
            toolUseMessage("call_1"),
            toolResultMessage("call_1", AiFence.fence("商品不存在"), isError = true),
        )

        assertEquals(ChatToolBlock.Status.FAILED, onlyToolBlock(AiChatSession.displaysFrom(history)).status)
    }

    @Test
    fun `没有结果的工具调用收成 CANCELLED —— 上次被中途杀掉`() {
        val block = onlyToolBlock(AiChatSession.displaysFrom(listOf(toolUseMessage("call_1"))))

        // 关键是「不为 RUNNING」：留在 RUNNING 就又是一个转不完的圈
        assertEquals(ChatToolBlock.Status.CANCELLED, block.status)
    }

    @Test
    fun `多个工具调用各补各的，不串号`() {
        val history = listOf(
            toolUseMessage("call_a"),
            toolResultMessage("call_a", AiFence.fence("A 的结果")),
            toolUseMessage("call_b"),
            toolResultMessage("call_b", AiFence.fence("B 的结果"), isError = true),
        )

        val blocks = AiChatSession.displaysFrom(history)
            .flatMap { it.blocks }.filterIsInstance<ChatBlock.Tool>().map { it.block }
        assertEquals(2, blocks.size)
        assertEquals("A 的结果", blocks.first { it.id == "call_a" }.resultPreview)
        assertEquals(ChatToolBlock.Status.SUCCESS, blocks.first { it.id == "call_a" }.status)
        assertEquals("B 的结果", blocks.first { it.id == "call_b" }.resultPreview)
        assertEquals(ChatToolBlock.Status.FAILED, blocks.first { it.id == "call_b" }.status)
    }

    @Test
    fun `纯工具结果的消息不单独生成一个气泡`() {
        val displays = AiChatSession.displaysFrom(
            listOf(toolUseMessage("call_1"), toolResultMessage("call_1", AiFence.fence("x"))),
        )
        // 结果只是补到上一条 assistant 的块上，自己不占一条消息
        assertEquals(1, displays.size)
    }

    // ---- 摘要成型 ----

    @Test
    fun `摘要剥掉围栏标记 —— 界面上不该出现划给模型看的边界`() {
        val preview = ChatDisplayMessage.toolPreview(AiFence.fence("找到 3 个商品"))

        assertEquals("找到 3 个商品", preview)
        assertTrue(AiFence.DATA_OPEN !in preview!!)
        assertTrue(AiFence.DATA_CLOSE !in preview)
    }

    @Test
    fun `没有围栏的文本原样保留 —— 阻断说明这类控制文案只消毒不围栏`() {
        assertEquals("该商品你还没看过", ChatDisplayMessage.toolPreview("该商品你还没看过"))
    }

    @Test
    fun `空结果没有摘要，不给一个能点开的空壳`() {
        assertNull(ChatDisplayMessage.toolPreview(null))
        assertNull(ChatDisplayMessage.toolPreview(""))
        assertNull(ChatDisplayMessage.toolPreview("   "))
        assertNull(ChatDisplayMessage.toolPreview(AiFence.fence("")))
    }

    @Test
    fun `超长按码点截断并加省略号`() {
        val body = "商".repeat(ChatDisplayMessage.TOOL_PREVIEW_MAX_CHARS + 50)
        val preview = ChatDisplayMessage.toolPreview(AiFence.fence(body))

        assertNotNull(preview)
        assertEquals(
            ChatDisplayMessage.TOOL_PREVIEW_MAX_CHARS,
            preview!!.removeSuffix("…").codePointCount(0, preview.removeSuffix("…").length),
        )
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun `截断不切断代理对 —— above-BMP 字符要么整个在要么整个不在`() {
        // 每个 emoji 是一个码点、两个 UTF-16 char。按 char 截会切出半个字符。
        val body = "🍼".repeat(ChatDisplayMessage.TOOL_PREVIEW_MAX_CHARS + 20)
        val preview = ChatDisplayMessage.toolPreview(AiFence.fence(body))!!
        val kept = preview.removeSuffix("…")

        assertEquals(ChatDisplayMessage.TOOL_PREVIEW_MAX_CHARS, kept.codePointCount(0, kept.length))
        // 逐字相等即证明没切出半个字符：按 UTF-16 char 截断会在结尾留一个落单代理项，
        // 那样这个断言必红
        assertEquals("🍼".repeat(ChatDisplayMessage.TOOL_PREVIEW_MAX_CHARS), kept)
    }
}
