package com.chunland.app

import com.chunland.app.core.ai.domain.AgentContentPart
import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.domain.AgentToolInput
import com.chunland.app.core.ai.domain.AgentTurnResult
import com.chunland.app.core.ai.loop.AgentMutationIntent
import com.chunland.app.core.ai.loop.AgentPreparedMutation
import com.chunland.app.core.ai.loop.AgentToolExecuting
import com.chunland.app.core.ai.loop.AgentToolPipeline
import com.chunland.app.core.ai.loop.MutationConfirming
import com.chunland.app.core.ai.loop.ToolLoopDetector
import com.chunland.app.core.ai.prompt.AiFence
import com.chunland.app.core.ai.tools.AiProvenanceKind
import com.chunland.app.core.ai.tools.ProvenanceRecorder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话级 provenance —— 锁住「模型只能对本会话真见过的 id 下手」。
 *
 * 它挡的不是越权（那是服务端的事），是**模型把 id 记串或凭印象编一个**：
 * 编出来的 code 恰好存在时，服务端会照单全收，因为那确实是个合法商品。
 *
 * ⚠️ 与 iOS `AIProvenanceTests.swift` 逐条对应，加用例要两边一起加。
 * 「哪个工具约束哪类 id」的规则表由 `check-ai-parity.py` 第 6 节比对，不在这里重复。
 */
class AiProvenanceTest {

    // MARK: - 记录者

    @Test
    fun `记过的能查到，没记的查不到`() = runBlocking {
        val r = ProvenanceRecorder()
        r.record(AiProvenanceKind.PRODUCT, listOf("123456", "abcdef"))
        assertTrue(r.has(AiProvenanceKind.PRODUCT, "123456"))
        assertFalse(r.has(AiProvenanceKind.PRODUCT, "999999"))
        // 类别之间互不串味：订单 id 不能拿来当商品 code 用
        assertFalse(r.has(AiProvenanceKind.ORDER, "123456"))
    }

    @Test
    fun `unseen 只返回没见过的且保留原顺序`() = runBlocking {
        val r = ProvenanceRecorder()
        r.record(AiProvenanceKind.PRODUCT, listOf("b", "d"))
        // 顺序要保留 —— 拒绝话术要把没见过的原样列给模型
        assertEquals(listOf("a", "c", "e"), r.unseen(AiProvenanceKind.PRODUCT, listOf("a", "b", "c", "d", "e")))
        assertEquals(emptyList<String>(), r.unseen(AiProvenanceKind.PRODUCT, listOf("b", "d")))
    }

    @Test
    fun `种子在构造时即生效 —— 页面上下文的 id 不必先查一遍`() = runBlocking {
        val r = ProvenanceRecorder(mapOf(AiProvenanceKind.PRODUCT to listOf("777777")))
        assertTrue(r.has(AiProvenanceKind.PRODUCT, "777777"))
    }

    @Test
    fun `超过上限按先进先出淘汰`() = runBlocking {
        val r = ProvenanceRecorder()
        val cap = ProvenanceRecorder.CAP_PER_KIND
        r.record(AiProvenanceKind.PRODUCT, (1..cap).map { "p$it" })
        assertTrue(r.has(AiProvenanceKind.PRODUCT, "p1"))
        r.record(AiProvenanceKind.PRODUCT, listOf("newest"))
        assertFalse("最早的应被淘汰", r.has(AiProvenanceKind.PRODUCT, "p1"))
        assertTrue("最新的必须在", r.has(AiProvenanceKind.PRODUCT, "newest"))
        assertTrue("中间的不受影响", r.has(AiProvenanceKind.PRODUCT, "p2"))
    }

    @Test
    fun `空串不入库 —— 否则空参数会意外通过守卫`() = runBlocking {
        val r = ProvenanceRecorder()
        r.record(AiProvenanceKind.PRODUCT, listOf("", "ok"))
        assertFalse(r.has(AiProvenanceKind.PRODUCT, ""))
        assertTrue(r.has(AiProvenanceKind.PRODUCT, "ok"))
    }

    @Test
    fun `重复登记不会撑大容量`() = runBlocking {
        val r = ProvenanceRecorder()
        repeat(3) { r.record(AiProvenanceKind.PRODUCT, listOf("same")) }
        assertEquals(emptyList<String>(), r.unseen(AiProvenanceKind.PRODUCT, listOf("same")))
    }

    // MARK: - 管道接线
    //
    // 用真的 ProvenanceRecorder + 真的管道，只把「哪个工具查哪类 id」这一步做成假件。

    private class Executor(private val recorder: ProvenanceRecorder) : AgentToolExecuting {
        var executed = 0
        override suspend fun availableTools() = emptyList<AgentToolDefinition>()
        override suspend fun exists(name: String) = true
        override suspend fun isAvailable(name: String) = true
        override suspend fun unavailableMessage(name: String) = "不可用"
        override suspend fun isMutation(name: String) = name == "add_to_cart"
        override suspend fun prepare(name: String, input: AgentToolInput): AgentPreparedMutation {
            return AgentPreparedMutation.Ready(AgentMutationIntent("i", name, "加购")) {
                executed++
                "已加入购物车"
            }
        }
        override suspend fun execute(name: String, input: AgentToolInput): String {
            executed++
            return "ok"
        }
        override suspend fun provenanceRejection(name: String, input: AgentToolInput): String? {
            if (name != "add_to_cart") return null
            val code = input.string("product_code").orEmpty()
            if (code.isEmpty() || recorder.has(AiProvenanceKind.PRODUCT, code)) return null
            return "商品代码 $code 不在本次对话出现过的商品里。请先用 search_products 确认。不要原样重试本次调用。"
        }
    }

    private class CountingConfirmer : MutationConfirming {
        var calls = 0
        override suspend fun confirm(batch: List<AgentMutationIntent>): Boolean {
            calls++
            return true
        }
    }

    private fun entry(code: String) = AgentTurnResult.ToolEntry(
        id = "c1",
        name = "add_to_cart",
        input = toolInput(code),
        rawInput = """{"product_code":"$code"}""",
    )

    private fun def() = AgentToolDefinition("add_to_cart", "", emptyMap(), emptyList())

    private fun toolInput(code: String) = AgentToolInput(
        JsonObject(mapOf("product_code" to JsonPrimitive(code))),
    )

    @Test
    fun `没见过的 id 被拦下，工具一次都不执行`() = runBlocking {
        val recorder = ProvenanceRecorder()
        val executor = Executor(recorder)
        val confirmer = CountingConfirmer()
        val pipeline = AgentToolPipeline(executor, confirmer, ToolLoopDetector())

        val out = pipeline.executeBatch(listOf(entry("999999")), listOf(def()))
        val text = (out.single().part as AgentContentPart.ToolResult).text

        assertTrue("要给出可执行的下一步：$text", text.contains("search_products"))
        assertTrue("要禁止原样重试：$text", text.contains("不要原样重试"))
        assertEquals("被拦下的调用一次都不该执行", 0, executor.executed)
        assertFalse("阻断说明是指令不是数据，不该进围栏：$text", text.startsWith(AiFence.DATA_OPEN))
    }

    @Test
    fun `被拦下的变更不弹确认框 —— 否则用户点了确认才被告知没见过`() = runBlocking {
        val confirmer = CountingConfirmer()
        val pipeline = AgentToolPipeline(Executor(ProvenanceRecorder()), confirmer, ToolLoopDetector())
        pipeline.executeBatch(listOf(entry("999999")), listOf(def()))
        assertEquals(0, confirmer.calls)
    }

    @Test
    fun `见过的 id 正常放行`() = runBlocking {
        val recorder = ProvenanceRecorder()
        recorder.record(AiProvenanceKind.PRODUCT, listOf("123456"))
        val executor = Executor(recorder)
        val confirmer = CountingConfirmer()
        val pipeline = AgentToolPipeline(executor, confirmer, ToolLoopDetector())

        val out = pipeline.executeBatch(listOf(entry("123456")), listOf(def()))
        val text = (out.single().part as AgentContentPart.ToolResult).text
        assertEquals(1, executor.executed)
        assertEquals("放行的变更要正常走确认", 1, confirmer.calls)
        assertTrue(text.contains("已加入购物车"))
    }

    @Test
    fun `页面上下文种子让当页商品直接可加购`() = runBlocking {
        // 商品详情页 ✨ 一进来就说「加购」—— 这条不通过，provenance 就是个 bug 而不是护栏
        val recorder = ProvenanceRecorder(mapOf(AiProvenanceKind.PRODUCT to listOf("555555")))
        val executor = Executor(recorder)
        val pipeline = AgentToolPipeline(executor, CountingConfirmer(), ToolLoopDetector())
        pipeline.executeBatch(listOf(entry("555555")), listOf(def()))
        assertEquals(1, executor.executed)
        assertNull(executor.provenanceRejection("add_to_cart", toolInput("555555")))
    }
}
