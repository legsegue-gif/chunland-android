package com.chunland.app

import com.chunland.app.core.ai.domain.AgentContentPart
import com.chunland.app.core.ai.domain.AgentHistoryIntegrity
import com.chunland.app.core.ai.domain.AgentMessage
import com.chunland.app.core.ai.domain.AgentToolInput
import com.chunland.app.core.ai.storage.AiTextSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁两块跨端契约：
 *
 * 1. **分词规则与 iOS 逐字一致** —— 期望值全部取自 iOS `AITextSegmenter` 的实跑输出。
 *    一端改了规则而另一端没改，会导致索引与查询用不同切法，**搜索静默失效**
 *    （不报错、只是搜不到），本测试就是那道防线。
 *
 * 2. **配对完整性修复** —— 「工具调用与结果严格配对同序」是 agent 循环第一约束，
 *    历史裁剪 / 取消 / 流中断都会制造孤儿，修复逻辑必须可靠。
 */
class AiTextSegmenterTest {

    // MARK: - 分词（期望值 = iOS 实跑输出）

    @Test
    fun `segment 与 iOS 逐字一致`() {
        // 中文逐字切开，字母数字连续成词；**首尾各补一个空格**作词边界
        assertEquals(" 帮 我 找 100 元 内 的 坚 果 ", AiTextSegmenter.segment("帮我找100元内的坚果"))
        // 纯英文原样不动
        assertEquals(" order shipped to Nanjing ", AiTextSegmenter.segment("order shipped to Nanjing"))
        // 中英数混排：表意字与非表意字的边界都要断开
        assertEquals(" 订 单 ABC123 到 哪 了 ", AiTextSegmenter.segment("订单ABC123到哪了"))
        assertEquals(" 帮 我 看 看 购 物 车 ", AiTextSegmenter.segment("帮我看看购物车"))
        // 用户自己打的空格会被折叠（只影响索引，不影响原文展示）
        assertEquals(" 你 好 世 界 ", AiTextSegmenter.segment("你好 世界"))
        // 标点与符号一律作分隔并丢弃 —— 否则搜商品号、工具名永远命中不了
        assertEquals(" 8510974 夏 普 五 门 ", AiTextSegmenter.segment("[8510974] 夏普 五门"))
        assertEquals(" search products get cart ", AiTextSegmenter.segment("search_products、get_cart"))
        assertEquals(" C 和 Swift ", AiTextSegmenter.segment("C++ 和 Swift"))
        assertEquals("", AiTextSegmenter.segment("！？。、"))
        assertEquals("", AiTextSegmenter.segment(""))
    }

    @Test
    fun `likePatterns 与 iOS 逐字一致`() {
        // 分词后前后包 %：空格边界让它成为整词匹配
        assertEquals(listOf("% 坚 果 %"), AiTextSegmenter.likePatterns("坚果"))
        assertEquals(listOf("% 购 物 车 %"), AiTextSegmenter.likePatterns("购物车"))
        assertEquals(listOf("% 100 元 %"), AiTextSegmenter.likePatterns("100元"))
        assertEquals(listOf("% shipped %"), AiTextSegmenter.likePatterns("shipped"))
        // 多个词各自一条模式（调用方以 AND 拼接）
        assertEquals(listOf("% 订 单 %", "% 状 态 %"), AiTextSegmenter.likePatterns("订单 状态"))
        // 查询与索引用同一套分词，带符号的输入照样能对上
        assertEquals(listOf("% 8510974 %"), AiTextSegmenter.likePatterns("[8510974]"))
        assertEquals(listOf("% search products %"), AiTextSegmenter.likePatterns("search_products"))
        // LIKE 元字符进不了模式（分词已把它们当分隔丢掉），不会变成通配
        assertEquals(listOf("% 50 %"), AiTextSegmenter.likePatterns("-50%"))
        assertEquals(emptyList<String>(), AiTextSegmenter.likePatterns("   "))
    }

    @Test
    fun `2 字中文词必须能被切出来`() {
        // 这条是整个分词方案存在的理由：
        // unicode61 会把整段中文当一个 token，trigram 搜不到 2 字词，
        // 而「坚果 / 订单 / 退款 / 天气」正是中文最常用的词长。
        listOf("坚果", "订单", "退款", "天气").forEach { word ->
            val seg = AiTextSegmenter.segment("我想查一下$word 的情况")
            val pattern = AiTextSegmenter.likePatterns(word).single()
            // LIKE 的 % 去掉后就是要在索引文本里出现的子串
            val needle = pattern.trim('%')
            assertTrue("「$word」分词后应出现在索引文本里：seg=$seg needle=$needle",
                seg.contains(needle))
        }
    }

    @Test
    fun `LIKE 模式是整词匹配，不会误命中乱序或部分重叠`() {
        // 换掉 FTS5 后，「相邻且对齐边界」这条语义完全靠 seg 的空格与首尾补空撑着，
        // 这里用字符串包含直接模拟 SQL 的 LIKE，把这条语义钉死。
        fun matches(text: String, keyword: String): Boolean {
            val seg = AiTextSegmenter.segment(text)
            return AiTextSegmenter.likePatterns(keyword).all { seg.contains(it.trim('%')) }
        }

        assertTrue(matches("我想买点坚果", "坚果"))
        // 乱序不命中
        assertTrue(!matches("这个果很坚硬", "坚果"))
        // 词在开头 / 结尾都要命中（这正是首尾补空格的理由）
        assertTrue(matches("坚果不错", "坚果"))
        assertTrue(matches("我要买坚果", "坚果"))
        // 单字不该被更长的词吞掉边界：搜「果」应命中「坚果」里的「果」token
        assertTrue(matches("买点坚果", "果"))
        // 英文整词：搜 ship 不该命中 shipped
        assertTrue(matches("order shipped", "shipped"))
        assertTrue(!matches("order shipped", "ship"))
        // 多词 AND
        assertTrue(matches("订单已经发货", "订单 发货"))
        assertTrue(!matches("订单已经取消", "订单 发货"))
    }

    @Test
    fun `excerpt 命中词居中且不越界`() {
        val long = "为你找到三款高性价比的坚果礼盒，分别是巴旦木、腰果和核桃仁，" +
            "都在你说的一百元预算之内，可以直接加入购物车结算，也可以先收藏起来慢慢比较"
        val short = AiTextSegmenter.excerpt(long, "购物车", maxLength = 30)
        assertTrue("摘要应包含命中词：$short", short.contains("购物车"))
        // 30 字上限 + 最多两个省略号
        assertTrue("摘要长度应受控：${short.length}", short.length <= 32)

        // 短文本原样返回，不加省略号
        assertEquals("很短的一句话", AiTextSegmenter.excerpt("很短的一句话", "短", maxLength = 60))
    }

    @Test
    fun `highlightRanges 能定位全部命中`() {
        val text = "坚果很好吃，我要买坚果"
        val ranges = AiTextSegmenter.highlightRanges(text, "坚果")
        assertEquals(2, ranges.size)
        ranges.forEach { r ->
            assertEquals("坚果", text.substring(r.first, r.last + 1))
        }
    }

    // MARK: - 配对完整性

    @Test
    fun `孤儿工具结果被删除`() {
        // 结果没有对应的调用 —— 回发会被上游拒（未知的 tool_use_id）
        val history = listOf(
            AgentMessage.user("买点坚果"),
            AgentMessage.toolResults(
                listOf(AgentContentPart.ToolResult("orphan", "search_products", "结果", false))
            ),
        )
        val report = AgentHistoryIntegrity.scan(history)
        assertEquals(1, report.orphanToolResults.size)

        val fixed = AgentHistoryIntegrity.repair(history)
        assertTrue("修复后不应再有孤儿", AgentHistoryIntegrity.scan(fixed).isClean)
        // 整条消息只有孤儿结果 → 整条移除
        assertEquals(1, fixed.size)
    }

    @Test
    fun `孤儿工具调用被补上占位结果`() {
        // 调用没有结果 —— 模型会永远等下去
        val history = listOf(
            AgentMessage.user("买点坚果"),
            AgentMessage(
                AgentMessage.Role.ASSISTANT,
                listOf(AgentContentPart.ToolUse("t1", "search_products", AgentToolInput()))
            ),
        )
        assertEquals(1, AgentHistoryIntegrity.scan(history).orphanToolUses.size)

        val fixed = AgentHistoryIntegrity.repair(history)
        assertTrue(AgentHistoryIntegrity.scan(fixed).isClean)
        assertEquals(3, fixed.size)

        val injected = fixed[2].parts.first() as AgentContentPart.ToolResult
        assertEquals("t1", injected.id)
        assertTrue("占位结果必须标记为错误", injected.isError)
    }

    @Test
    fun `尾部中断消息整条丢弃且不再补占位`() {
        // 流中断的 assistant 消息里，工具参数可能只写了一半，回发必被拒。
        // 正确做法是整条丢掉从上一回合重来 —— 而不是给残缺的调用补结果。
        val history = listOf(
            AgentMessage.user("买点坚果"),
            AgentMessage(
                AgentMessage.Role.ASSISTANT,
                listOf(AgentContentPart.ToolUse("t1", "search_products", AgentToolInput())),
                isInterrupted = true,
            ),
        )
        assertNotNull(AgentHistoryIntegrity.scan(history).trailingInterruptedIndex)

        val fixed = AgentHistoryIntegrity.repair(history)
        assertEquals("中断消息应被移除，且不产生占位结果", 1, fixed.size)
        assertTrue(AgentHistoryIntegrity.scan(fixed).isClean)
    }

    @Test
    fun `正常配对的历史不被改动`() {
        val history = listOf(
            AgentMessage.user("买点坚果"),
            AgentMessage(
                AgentMessage.Role.ASSISTANT,
                listOf(AgentContentPart.ToolUse("t1", "search_products", AgentToolInput()))
            ),
            AgentMessage.toolResults(
                listOf(AgentContentPart.ToolResult("t1", "search_products", "找到 3 款", false))
            ),
            AgentMessage.assistant("为你找到三款坚果"),
        )
        assertTrue(AgentHistoryIntegrity.scan(history).isClean)
        assertEquals(history, AgentHistoryIntegrity.repair(history))
    }

    // MARK: - 工具参数

    @Test
    fun `工具参数解析失败返回空而不是抛错`() {
        // 模型发来的 JSON 截断是常态而非异常，由 preflight 与修复策略接手
        assertTrue(AgentToolInput.parse("{\"keyword\":").isEmpty)
        assertTrue(AgentToolInput.parse("").isEmpty)

        val ok = AgentToolInput.parse("{\"keyword\":\"坚果\",\"limit\":5}")
        assertEquals("坚果", ok.string("keyword"))
        assertEquals(5, ok.int("limit"))
    }

    @Test
    fun `空白必填值等同缺失`() {
        // 模型常发 {"path": ""} —— 键在但没内容，和缺键一样坏
        val input = AgentToolInput.parse("{\"a\":\"\",\"b\":\"  \",\"c\":\"x\"}")
        assertTrue(input.isBlank("a"))
        assertTrue(input.isBlank("b"))
        assertTrue(input.isBlank("missing"))
        assertTrue(!input.isBlank("c"))
    }

    @Test
    fun `数字与布尔值能按字符串读出`() {
        // 模型偶尔把字符串字段发成数字，宽容读取避免 handler 到处判类型
        val input = AgentToolInput.parse("{\"n\":123,\"b\":true}")
        assertEquals("123", input.string("n"))
        assertEquals(123, input.int("n"))
        assertEquals(true, input.bool("b"))
        assertNull(input.int("b"))
    }
}
