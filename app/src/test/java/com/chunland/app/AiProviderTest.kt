package com.chunland.app

import com.chunland.app.core.ai.domain.AgentContentPart
import com.chunland.app.core.ai.domain.AgentMessage
import com.chunland.app.core.ai.domain.AgentStopReason
import com.chunland.app.core.ai.domain.AgentToolInput
import com.chunland.app.core.ai.provider.LlmError
import com.chunland.app.core.ai.provider.ModelCatalog
import com.chunland.app.core.ai.provider.OpenAiWire
import com.chunland.app.core.ai.provider.ToolCallAssembler
import com.chunland.app.feature.ai.reorderedMembers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁 provider 层三块契约：
 *
 * 1. **错误双判定正交** —— isRetryable 与 isFallbackable 是两个独立提问，
 *    不是互斥的两类。混成一个会让「重试几次后换模型」无法表达。
 * 2. **wire 编码** —— 工具结果必须拆成独立的 role:"tool" 帧且保持同序；
 *    这是「调用与结果严格配对同序」这条第一约束在传输层的体现。
 * 3. **工具调用分片累积** —— 标准 OpenAI 的字符切片与部分兼容端的一次性返回
 *    必须走同一条路径。
 */
class AiProviderTest {

    // MARK: - 错误分类

    @Test
    fun `重试与降级是两个正交判定`() {
        // 网络与瞬时错误：原地重试有意义，但它们本身不触发换模型
        // （重试耗尽后由调用方策略决定是否降级）
        val transient = LlmError.TransientError("HTTP 503")
        assertTrue(transient.isRetryable)
        assertFalse(transient.isFallbackable)

        val network = LlmError.NetworkError("timeout")
        assertTrue(network.isRetryable)
        assertFalse(network.isFallbackable)

        // provider 级错误：换模型，不在当前模型上重试（重试必然再失败）
        listOf(
            LlmError.RateLimited(),
            LlmError.InvalidApiKey("bad key"),
            LlmError.ProviderError("model not found"),
            LlmError.SystemProviderUnavailable("维护中"),
        ).forEach { e ->
            assertFalse("${e.javaClass.simpleName} 不该原地重试", e.isRetryable)
            assertTrue("${e.javaClass.simpleName} 应触发降级", e.isFallbackable)
        }

        // 取消：既不重试也不降级
        assertFalse(LlmError.Cancelled.isRetryable)
        assertFalse(LlmError.Cancelled.isFallbackable)
        assertTrue(LlmError.Cancelled.isCancellation)
    }

    @Test
    fun `HTTP 状态按处置方式分类而不是按码段`() {
        // 401/403 → 要用户改配置
        assertTrue(LlmError.fromHttpStatus(401) is LlmError.InvalidApiKey)
        assertTrue(LlmError.fromHttpStatus(403) is LlmError.InvalidApiKey)
        // 429 → 等或换号
        assertTrue(LlmError.fromHttpStatus(429) is LlmError.RateLimited)
        // 5xx → 等一会儿多半自己好
        listOf(500, 502, 503, 504, 529).forEach {
            assertTrue("HTTP $it 应为瞬时错误", LlmError.fromHttpStatus(it) is LlmError.TransientError)
        }
        // 其余 4xx → 请求本身有问题，重试没用
        assertTrue(LlmError.fromHttpStatus(400) is LlmError.ProviderError)
        assertTrue(LlmError.fromHttpStatus(404) is LlmError.ProviderError)
    }

    @Test
    fun `用户可见文案不含原始状态码`() {
        // 「HTTP 429」对用户是天书
        val text = LlmError.fromHttpStatus(429).userMessage
        assertFalse(text.contains("429"))
        assertTrue(text.contains("频繁") || text.contains("请求较多"))
    }

    // MARK: - wire 编码

    @Test
    fun `工具结果被拆成独立的 tool 帧且保持同序`() {
        val history = listOf(
            AgentMessage.user("找点坚果"),
            AgentMessage(
                AgentMessage.Role.ASSISTANT,
                listOf(
                    AgentContentPart.Text("我来查一下"),
                    AgentContentPart.ToolUse("t1", "search_products", AgentToolInput()),
                    AgentContentPart.ToolUse("t2", "get_cart", AgentToolInput()),
                )
            ),
            AgentMessage.toolResults(
                listOf(
                    AgentContentPart.ToolResult("t1", "search_products", "找到 3 款", false),
                    AgentContentPart.ToolResult("t2", "get_cart", "购物车为空", false),
                )
            ),
        )

        val wire = OpenAiWire.encode(history, systemPrompt = "你是助手", loadImage = null)
        val roles = wire.map { it["role"]?.jsonPrimitive?.contentOrNull }
        assertEquals(listOf("system", "user", "assistant", "tool", "tool"), roles)

        // assistant 帧同时带文本与两个调用
        val assistant = wire[2]
        assertEquals("我来查一下", assistant["content"]?.jsonPrimitive?.contentOrNull)
        val calls = assistant["tool_calls"]!!.jsonArray
        assertEquals(2, calls.size)
        assertEquals("t1", calls[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("t2", calls[1].jsonObject["id"]?.jsonPrimitive?.contentOrNull)

        // tool 帧顺序必须与 tool_calls 一致
        assertEquals("t1", wire[3]["tool_call_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("t2", wire[4]["tool_call_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `空 assistant 帧被丢弃`() {
        // 既无文本也无工具调用的 assistant 帧会被部分端点判为非法
        val wire = OpenAiWire.encode(
            listOf(AgentMessage(AgentMessage.Role.ASSISTANT, emptyList())),
            systemPrompt = null,
            loadImage = null,
        )
        assertTrue(wire.isEmpty())
    }

    @Test
    fun `纯文本 user 帧编成字符串而非数组`() {
        // 有的端点对 content 数组的支持只覆盖带图场景，纯文本走字符串兼容性更好
        val wire = OpenAiWire.encode(
            listOf(AgentMessage.user("你好")),
            systemPrompt = null,
            loadImage = null,
        )
        assertEquals(1, wire.size)
        assertEquals("你好", wire[0]["content"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `stream 必须显式落 wire`() {
        // 用默认值省略会让部分端点退化成非流式，表现是「转圈然后整段吐出来」
        val body = OpenAiWire.requestBody(
            model = "m", messages = emptyList(), tools = emptyList(),
            maxTokens = 100, temperature = null,
        )
        assertNotNull("stream 字段必须存在", body["stream"])
        assertEquals(true, body["stream"]?.jsonPrimitive?.content?.toBoolean())
        // 没有工具时不该带 tools/tool_choice
        assertNull(body["tools"])
        assertNull(body["tool_choice"])
    }

    // MARK: - SSE 解析

    @Test
    fun `content delta 与两种 reasoning 字段名都能解析`() {
        val (d1, _) = OpenAiWire.parseChunk(
            """{"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}"""
        )!!
        assertEquals("你好", d1?.content)

        // reasoning_content（较常见）
        val (d2, _) = OpenAiWire.parseChunk(
            """{"choices":[{"delta":{"reasoning_content":"思考中"}}]}"""
        )!!
        assertEquals("思考中", d2?.reasoning)

        // reasoning（部分适配器变体）
        val (d3, _) = OpenAiWire.parseChunk(
            """{"choices":[{"delta":{"reasoning":"思考中"}}]}"""
        )!!
        assertEquals("思考中", d3?.reasoning)
    }

    @Test
    fun `内嵌错误帧能被识别`() {
        // HTTP 200 但 body 是错误 —— 上游过载时的常见形态。
        // 不识别的话流会静默结束，表现为「AI 什么都没说」。
        assertEquals(
            "服务过载",
            OpenAiWire.parseErrorPayload("""{"error":{"message":"服务过载","type":"overloaded"}}""")
        )
        // 正常 chunk 不该被误判成错误
        assertNull(
            OpenAiWire.parseErrorPayload("""{"choices":[{"delta":{"content":"hi"}}]}""")
        )
    }

    @Test
    fun `finish_reason 映射`() {
        assertEquals(AgentStopReason.END_TURN, OpenAiWire.stopReason("stop"))
        assertEquals(AgentStopReason.TOOL_USE, OpenAiWire.stopReason("tool_calls"))
        assertEquals(AgentStopReason.MAX_TOKENS, OpenAiWire.stopReason("length"))
        // 内容过滤 = 确定性拒答，必须与 END_TURN 分开（重试必然再被拒）
        assertEquals(AgentStopReason.REFUSED, OpenAiWire.stopReason("content_filter"))
        assertNull(OpenAiWire.stopReason(null))
    }

    // MARK: - 工具调用累积

    @Test
    fun `标准分片与一次性返回走同一条路径`() {
        // 标准 OpenAI：按 index 累积字符切片
        val sliced = ToolCallAssembler()
        sliced.accept(listOf(OpenAiWire.ToolCallDelta(0, "call_1", "search_products", "")))
        sliced.accept(listOf(OpenAiWire.ToolCallDelta(0, null, null, """{"key""")))
        sliced.accept(listOf(OpenAiWire.ToolCallDelta(0, null, null, """word":"坚果"}""")))
        val a = sliced.finish()
        assertEquals(1, a.size)
        assertEquals("坚果", a[0].input.string("keyword"))

        // 部分兼容端：一次性给完整的 tool_calls
        val whole = ToolCallAssembler()
        whole.accept(listOf(
            OpenAiWire.ToolCallDelta(0, "call_1", "search_products", """{"keyword":"坚果"}""")
        ))
        val b = whole.finish()
        assertEquals(a[0].input.string("keyword"), b[0].input.string("keyword"))
    }

    @Test
    fun `多工具按 index 升序产出`() {
        // 顺序即 wire 上的顺序 —— 工具结果必须按同序回填，否则配对错位
        val asm = ToolCallAssembler()
        asm.accept(listOf(
            OpenAiWire.ToolCallDelta(1, "b", "get_cart", "{}"),
            OpenAiWire.ToolCallDelta(0, "a", "search_products", "{}"),
        ))
        val out = asm.finish()
        assertEquals(listOf("a", "b"), out.map { it.id })
    }

    @Test
    fun `缺 id 时补一个而不是丢掉`() {
        // 某些端点在单工具调用时省略 id，但配对约束要求它必须存在
        val asm = ToolCallAssembler()
        asm.accept(listOf(OpenAiWire.ToolCallDelta(0, null, "get_cart", "{}")))
        val out = asm.finish()
        assertEquals(1, out.size)
        assertTrue("应补出 id", out[0].id.startsWith("call_"))
    }

    @Test
    fun `无名字的槽位被丢弃`() {
        // 只有参数没有函数名的分片是残缺数据，执行不了
        val asm = ToolCallAssembler()
        asm.accept(listOf(OpenAiWire.ToolCallDelta(0, "x", null, """{"a":1}""")))
        assertTrue(asm.finish().isEmpty())
    }

    // MARK: - 模型列表拉取

    @Test
    fun `两种响应形状都能解析`() {
        // 标准形状
        assertEquals(
            listOf("gpt-4o", "o3"),
            ModelCatalog.parseModelIds("""{"object":"list","data":[{"id":"o3"},{"id":"gpt-4o"}]}"""),
        )
        // 非标准：直接返回数组。不为一个不规范的端点把功能判死
        assertEquals(
            listOf("a", "b"),
            ModelCatalog.parseModelIds("""[{"id":"b"},{"id":"a"}]"""),
        )
    }

    @Test
    fun `模型列表去重后按名排序`() {
        // 端点返回的顺序通常是创建时间，对找模型没帮助
        assertEquals(
            listOf("alpha", "beta", "gamma"),
            ModelCatalog.parseModelIds("""{"data":[{"id":"gamma"},{"id":"alpha"},{"id":"beta"},{"id":"alpha"}]}"""),
        )
    }

    @Test
    fun `看不懂的响应报错而不是给空列表`() {
        // 空列表会让用户以为「这个端点一个模型都没有」，与「不支持列出模型」是两回事
        listOf("""{"foo":1}""", "not json", """{"data":[{"name":"x"}]}""", "[]").forEach { body ->
            try {
                ModelCatalog.parseModelIds(body)
                throw AssertionError("应抛出 FetchException：$body")
            } catch (e: ModelCatalog.FetchException) {
                assertTrue(e.message!!.contains("手动填写"))
            }
        }
    }

    // MARK: - 降级链排序（可见下标 → 完整列表）

    @Test
    fun `移动用的是可见下标而完整列表里夹着停用项`() {
        // 完整链：[a, X, b, c]，X 是停用项（不显示）。可见列表 = [a, b, c]
        val full = listOf("a", "X", "b", "c")
        val visible = listOf("a", "b", "c")

        // 把可见的第 2 项（b）上移一位 —— 直接对完整数组做 move(index=1) 会挪到 X
        val up = reorderedMembers(full, visible, index = 1, offset = -1)
        assertEquals(listOf("b", "X", "a", "c"), up)
        // 停用项 X 仍在原槽位：重新启用时回到原来的位置，而不是被挤到链尾
        assertEquals(1, up.indexOf("X"))

        val down = reorderedMembers(full, visible, index = 0, offset = 1)
        assertEquals(listOf("b", "X", "a", "c"), down)
    }

    @Test
    fun `移出边界时原样返回`() {
        val full = listOf("a", "b")
        assertEquals(full, reorderedMembers(full, full, index = 0, offset = -1))
        assertEquals(full, reorderedMembers(full, full, index = 1, offset = 1))
    }
}
