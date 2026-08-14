package com.chunland.app

import com.chunland.app.core.ai.domain.AgentContentPart
import com.chunland.app.core.ai.domain.AgentMessage
import com.chunland.app.core.ai.domain.AgentParamType
import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.domain.AgentToolInput
import com.chunland.app.core.ai.domain.AgentToolParam
import com.chunland.app.core.ai.loop.ContextPolicy
import com.chunland.app.core.ai.loop.LoopLevel
import com.chunland.app.core.ai.loop.TokenEstimator
import com.chunland.app.core.ai.loop.ToolArgsRepair
import com.chunland.app.core.ai.domain.AgentTurnResult
import com.chunland.app.core.ai.loop.AgentToolExecuting
import com.chunland.app.core.ai.loop.AgentToolPipeline
import com.chunland.app.core.ai.loop.AgentMutationIntent
import com.chunland.app.core.ai.loop.AgentPreparedMutation
import com.chunland.app.core.ai.loop.MutationConfirming
import kotlinx.coroutines.runBlocking
import com.chunland.app.core.ai.loop.ToolLoopDetector
import com.chunland.app.core.ai.loop.ToolLoopConfig
import com.chunland.app.core.ai.prompt.AiPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁循环层四块契约：
 *
 * 1. **循环检测四策略** —— 这是放开轮次上限（8→30）的前提。检测失效 =
 *    打转的模型把 30 轮全烧完，比原来更糟。
 * 2. **参数修复三策略** —— 尤其「数组/字典刻意不转」这条负向约束。
 * 3. **上下文分档与 token 估算**。
 * 4. **prompt 的关键约束句必须在场** —— 执行纪律与压缩规则是行为的直接来源，
 *    被误删会静默降低 AI 表现。
 */
class AiLoopTest {

    private fun toolDef(vararg required: String) = AgentToolDefinition(
        name = "search_products",
        description = "搜索商品",
        parameters = required.associateWith {
            AgentToolParam(AgentParamType.STRING, "参数 $it")
        },
        required = required.toList(),
    )

    // MARK: - 循环检测

    @Test
    fun `身份不可用工具连击 3 次熔断`() {
        // 阈值刻意比其它低：拒绝话术已明说要切身份，3 次没听懂再喊也没用
        val d = ToolLoopDetector()
        val input = AgentToolInput.parse("""{"a":1}""")
        repeat(2) { d.record("add_to_cart", input, null, unavailable = true) }
        val level = d.check("add_to_cart", input)
        assertTrue("第 3 次应熔断，实际 $level", level is LoopLevel.Blocked)
        assertTrue((level as LoopLevel.Blocked).message.contains("切换身份"))
    }

    @Test
    fun `未知工具连击 5 次熔断`() {
        val d = ToolLoopDetector()
        val input = AgentToolInput()
        repeat(4) { d.record("no_such_tool", input, null, unknown = true) }
        assertTrue(d.check("no_such_tool", input) is LoopLevel.Blocked)
    }

    @Test
    fun `结果在变就不算无进展`() {
        // 「结果相同」是关键判据：参数一样但结果在变说明确实在推进
        val d = ToolLoopDetector(ToolLoopConfig(pollWarningThreshold = 3, pollCriticalThreshold = 5))
        val input = AgentToolInput.parse("""{"order_id":1}""")
        d.record("get_order_detail", input, "PENDING")
        d.record("get_order_detail", input, "PAID")       // 变了
        d.record("get_order_detail", input, "PURCHASING") // 又变了
        assertEquals(LoopLevel.Pass, d.check("get_order_detail", input))
    }

    @Test
    fun `轮询无进展先警告后熔断`() {
        val d = ToolLoopDetector(ToolLoopConfig(pollWarningThreshold = 3, pollCriticalThreshold = 5))
        val input = AgentToolInput.parse("""{"order_id":1}""")
        repeat(2) { d.record("get_order_detail", input, "PENDING") }

        val warn = d.check("get_order_detail", input)
        assertTrue("应先警告，实际 $warn", warn is LoopLevel.Warning)

        repeat(2) { d.record("get_order_detail", input, "PENDING") }
        assertTrue(d.check("get_order_detail", input) is LoopLevel.Blocked)
    }

    @Test
    fun `同一警告只发一次`() {
        // 反复喊会挤占上下文
        val d = ToolLoopDetector(ToolLoopConfig(repeatWarningThreshold = 2))
        val input = AgentToolInput.parse("""{"keyword":"坚果"}""")
        repeat(2) { d.record("do_something", input, "same") }
        assertTrue(d.check("do_something", input) is LoopLevel.Warning)
        // 第二次同 key 不再警告
        assertEquals(LoopLevel.Pass, d.check("do_something", input))
    }

    @Test
    fun `等价参数得到同一个哈希`() {
        // 键顺序不同但内容相同，必须判定为「同一次调用」
        val a = AgentToolInput.parse("""{"a":1,"b":"x"}""")
        val b = AgentToolInput.parse("""{"b":"x","a":1}""")
        assertEquals(ToolLoopDetector.canonical(a), ToolLoopDetector.canonical(b))
    }

    // MARK: - 参数修复

    @Test
    fun `截断的 JSON 能补全`() {
        // 模型偶尔在写完对象前被切断
        val out = ToolArgsRepair.repair(
            name = "search_products",
            input = AgentToolInput(),               // 解析失败 → 空
            rawInput = """{"keyword":"坚果""",       // 原始尾巴
            definition = toolDef("keyword"),
        )
        assertTrue("应修复，repairs=${out.repairs}", out.didRepair)
        assertEquals("坚果", out.input.string("keyword"))
    }

    @Test
    fun `数字被转成字符串`() {
        val out = ToolArgsRepair.repair(
            name = "search_products",
            input = AgentToolInput.parse("""{"keyword":123}"""),
            rawInput = "",
            definition = toolDef("keyword"),
        )
        assertEquals("123", out.input.string("keyword"))
    }

    @Test
    fun `数组和字典刻意不转换`() {
        // 转成的调试字符串会被下游当成真实值（把 ["a","b"] 当字面路径），
        // 破坏性远大于直接让 preflight 拒绝
        val out = ToolArgsRepair.repair(
            name = "search_products",
            input = AgentToolInput.parse("""{"keyword":["a","b"]}"""),
            rawInput = "",
            definition = toolDef("keyword"),
        )
        assertFalse("数组不该被转换", out.repairs.any { it.startsWith("类型转换") })
    }

    @Test
    fun `字段名打错一个字母能纠正`() {
        val out = ToolArgsRepair.repair(
            name = "search_products",
            input = AgentToolInput.parse("""{"keywrd":"坚果"}"""),
            rawInput = "",
            definition = toolDef("keyword"),
        )
        assertEquals("坚果", out.input.string("keyword"))
        assertTrue(out.repairs.any { it.contains("字段纠错") })
    }

    @Test
    fun `差太远的字段名不乱认`() {
        // name→code 距离 4，认了就是在猜
        assertEquals(null, ToolArgsRepair.nearestKey("code", listOf("name"), maxDistance = 1))
        assertEquals("comand", ToolArgsRepair.nearestKey("command", listOf("comand"), maxDistance = 1))
    }

    @Test
    fun `参数完好时零开销不动它`() {
        val input = AgentToolInput.parse("""{"keyword":"坚果"}""")
        val out = ToolArgsRepair.repair("search_products", input, "", toolDef("keyword"))
        assertFalse(out.didRepair)
    }

    // MARK: - 上下文分档

    @Test
    fun `小窗口不自动压缩只提示新开`() {
        // 压缩本身要占掉一大块，小模型上得不偿失
        val p = ContextPolicy(16_000)
        assertEquals(0, p.compactThreshold)
        assertTrue(p.exhaustedOnly)
        assertFalse(p.manualCompactAllowed)
        assertEquals(ContextPolicy.Decision.EXHAUSTED, p.decide(15_000))
    }

    @Test
    fun `大窗口先卸载后压缩`() {
        val p = ContextPolicy(200_000)
        assertEquals(160_000, p.offloadThreshold)
        assertEquals(180_000, p.compactThreshold)
        assertFalse(p.exhaustedOnly)

        assertEquals(ContextPolicy.Decision.OK, p.decide(100_000))
        assertTrue(p.shouldOffload(165_000))
        assertEquals(ContextPolicy.Decision.NEEDS_COMPACT, p.decide(185_000))
    }

    @Test
    fun `token 估算按字符类型加权`() {
        // 中文约 1 token/字
        assertTrue(TokenEstimator.estimate("你好世界") in 3..5)
        // 英文约 1 token/4 字符
        assertTrue(TokenEstimator.estimate("hello world") in 2..4)
        // 图片按固定值高估（低估会让上下文悄悄溢出，那是硬失败）
        val withImage = AgentMessage(
            AgentMessage.Role.USER,
            listOf(AgentContentPart.Image(
                com.chunland.app.core.ai.domain.MediaRef("i", "s", "p", "image/jpeg", 100)
            ))
        )
        assertTrue(TokenEstimator.estimate(withImage) >= 800)
    }

    // MARK: - prompt 关键约束

    @Test
    fun `执行纪律必须包含不承诺未来动作`() {
        // 这是「显得聪明」最直接的来源：模型说「我会持续关注」然后静默，
        // 是通用 agent 上反复出现的失败模式
        val p = AiPrompts.system()
        assertTrue("必须禁止承诺未来动作", p.contains("绝不以") && p.contains("承诺"))
        assertTrue("必须解释回合结束后什么都不会发生", p.contains("回合一结束"))
        assertTrue("必须要求直接调工具", p.contains("直接调"))
    }

    @Test
    fun `压缩 prompt 必须强制过去时且禁止待办清单`() {
        // 摘要写成 todo 会被模型当成没干完的工单继续执行
        val c = AiPrompts.compaction
        assertTrue(c.contains("过去时"))
        assertTrue(c.contains("不要写成待办清单") || c.contains("不要写成待办"))
        assertTrue(c.contains("已完成的事"))
    }

    @Test
    fun `工具规则必须禁止索要地址与自算费用`() {
        // 旧契约让模型收集姓名电话地址，导致 areaCode 丢失 → 距离费静默为 0，
        // 且地址 PII 进了模型上下文
        val p = AiPrompts.system()
        assertTrue(p.contains("绝不向用户索要姓名、电话、收货地址"))
        assertTrue(p.contains("不要自己估算费用"))
    }

    @Test
    fun `system prompt 按需拼接上下文与画像`() {
        val bare = AiPrompts.system()
        assertFalse(bare.contains("当前上下文"))

        val full = AiPrompts.system(pageContext = "用户正在逛「测试店」", userProfile = "默认地址在南京")
        assertTrue(full.contains("当前上下文"))
        assertTrue(full.contains("测试店"))
        assertTrue(full.contains("关于当前用户"))
        assertTrue(full.contains("南京"))
    }

    // MARK: - 管道与检测器的接线
    //
    // 检测器单测过不代表接线对：真正的 bug 出在**管道怎么记账**。
    // 模拟器实测发现「不可用工具连击 3 次熔断」从未生效 —— 被阻断的那次
    // 记成了普通调用，把连击链自己打断，只能等 15 次的全局熔断兜底。

    private class FakeExecutor(private val availableTools: Set<String>) : AgentToolExecuting {
        var executed = 0
        override suspend fun availableTools() = emptyList<AgentToolDefinition>()
        override suspend fun exists(name: String) = true
        override suspend fun isAvailable(name: String) = name in availableTools
        override suspend fun unavailableMessage(name: String) =
            "工具 $name 在当前身份（代购人）下不可用，此操作需要买家身份。不要重试本工具。"
        override suspend fun isMutation(name: String) = name.startsWith("add_") || name.startsWith("place_")
        override suspend fun prepare(name: String, input: AgentToolInput) =
            AgentPreparedMutation.Ready(AgentMutationIntent("i", name, "做点什么")) { "ok" }
        override suspend fun execute(name: String, input: AgentToolInput): String {
            executed++
            return "ok"
        }
    }

    private object AlwaysApprove : MutationConfirming {
        override suspend fun confirm(batch: List<AgentMutationIntent>) = true
    }

    private var seq = 0

    /** preflight 以传入的 tools 为准：不给定义就会被判成未知工具 */
    private fun def(name: String) = AgentToolDefinition(
        name = name, description = "", parameters = emptyMap(), required = emptyList(),
    )

    private fun entry(name: String) = AgentTurnResult.ToolEntry(
        id = "call-" + name + "-" + (seq++),
        name = name,
        input = AgentToolInput(),
        rawInput = "{}",
    )

    @Test
    fun `管道对不可用工具连击 3 次即熔断，不必等全局熔断`() = runBlocking {
        val executor = FakeExecutor(availableTools = emptySet())
        val detector = ToolLoopDetector()
        val pipeline = AgentToolPipeline(executor, AlwaysApprove, detector)

        val texts = (1..5).map {
            val out = pipeline.executeBatch(listOf(entry("add_to_cart")), listOf(def("add_to_cart")))
            (out.single().part as AgentContentPart.ToolResult).text
        }
        fun blocked(t: String) = t.contains("已连续") && t.contains("不可用的工具")

        // 前两次是普通的身份拒绝 —— 阈值是「连续 3 次」，早于 3 次熔断说明记账被数重了
        assertTrue("第 1 次应是身份拒绝：${texts[0]}", texts[0].contains("需要买家身份"))
        assertTrue("第 2 次不该熔断：${texts[1]}", !blocked(texts[1]))
        // 第 3 次起熔断，且**必须持续熔断** —— 被阻断的那次若记成普通调用，
        // 连击链会被自己打断，第 4 次又退回普通拒绝（这正是实测发现的 bug）
        assertTrue("第 3 次该熔断：${texts[2]}", blocked(texts[2]))
        assertTrue("第 4 次仍应熔断（连击链不能被自己打断）：${texts[3]}", blocked(texts[3]))
        assertTrue("第 5 次仍应熔断：${texts[4]}", blocked(texts[4]))
        assertEquals("不可用的工具一次都不该真执行", 0, executor.executed)
    }

    @Test
    fun `可用工具正常执行，不受不可用连击影响`() = runBlocking {
        val executor = FakeExecutor(availableTools = setOf("get_cart"))
        val pipeline = AgentToolPipeline(executor, AlwaysApprove, ToolLoopDetector())
        val out = pipeline.executeBatch(listOf(entry("get_cart")), listOf(def("get_cart")))
        assertEquals(1, executor.executed)
        assertTrue((out.single().part as AgentContentPart.ToolResult).text.contains("ok"))
    }

}
