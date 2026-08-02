package com.chunland.app

import com.chunland.app.core.ai.AiContext
import com.chunland.app.core.ai.AiFunctionWire
import com.chunland.app.core.ai.AiParametersWire
import com.chunland.app.core.ai.AiToolName
import com.chunland.app.core.ai.AiToolWire
import com.chunland.app.core.ai.AiWireJson
import com.chunland.app.core.ai.ChatWireMessage
import com.chunland.app.core.ai.ChatWireRequest
import com.chunland.app.core.ai.StreamToolCallDelta
import com.chunland.app.core.ai.ToolCallAssembler
import com.chunland.app.core.ai.WireToolCall
import com.chunland.app.core.ai.WireToolCallFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁 AI 工具循环三块契约：
 * 1. ToolCallAssembler —— 标准 OpenAI 按 index 分片累积 / 一次性完整返回，同一路径；
 * 2. 请求 wire —— stream:true / type:"function" 等常量默认值必须落 wire
 *    （encodeDefaults=false 时 stream 被吞、真实 endpoint 退化非流式的回归锁）；
 * 3. 工具分级与身份门控 —— mutation 集合、agent/merchant 域仅对应身份可见。
 */
class AiToolLoopTest {

    private fun delta(
        index: Int,
        id: String? = null,
        type: String? = null,
        name: String? = null,
        args: String? = null,
    ) = StreamToolCallDelta(
        index = index, id = id, type = type,
        function = if (name == null && args == null) null else StreamToolCallDelta.FunctionDelta(name, args),
    )

    @Test
    fun `assembler accumulates sliced arguments by index`() {
        val a = ToolCallAssembler()
        a.add(delta(0, id = "call_1", type = "function", name = "search_products", args = ""))
        a.add(delta(0, args = """{"query":"""))
        a.add(delta(0, args = """"牛排"}"""))
        val calls = a.build()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("search_products", calls[0].function.name)
        assertEquals("""{"query":"牛排"}""", calls[0].function.arguments)
    }

    @Test
    fun `assembler accepts one-shot complete call (one-shot flavor)`() {
        val a = ToolCallAssembler()
        a.add(delta(0, id = "c9", type = "function", name = "get_cart", args = "{}"))
        val calls = a.build()
        assertEquals(1, calls.size)
        assertEquals("{}", calls[0].function.arguments)
    }

    @Test
    fun `assembler keeps multiple calls ordered by index and drops incomplete`() {
        val a = ToolCallAssembler()
        a.add(delta(1, id = "c2", name = "get_cart", args = "{}"))
        a.add(delta(0, id = "c1", name = "search_products", args = """{"query":"a"}"""))
        a.add(delta(2, name = "orphan", args = "{}"))   // 无 id → 丢弃
        val calls = a.build()
        assertEquals(listOf("c1", "c2"), calls.map { it.id })
    }

    @Test
    fun `request wire carries stream=true, tools and tool_choice`() {
        val tool = AiToolWire(
            function = AiFunctionWire(
                name = "get_cart",
                description = "查看购物车",
                parameters = AiParametersWire(),
            ),
        )
        val json = AiWireJson.encodeToString(
            ChatWireRequest.serializer(),
            ChatWireRequest(
                model = "m",
                messages = listOf(ChatWireMessage("user", "hi")),
                tools = listOf(tool),
                toolChoice = "auto",
            ),
        )
        assertTrue(json.contains(""""stream":true"""))
        assertTrue(json.contains(""""tool_choice":"auto""""))
        assertTrue(json.contains(""""type":"function""""))
        assertTrue(json.contains(""""type":"object""""))
    }

    @Test
    fun `tool history messages carry tool_call_id and tool_calls`() {
        val call = WireToolCall(id = "call_1", function = WireToolCallFunction("get_cart", "{}"))
        val assistant = AiWireJson.encodeToString(
            ChatWireMessage.serializer(),
            ChatWireMessage("assistant", null, toolCalls = listOf(call)),
        )
        assertTrue(assistant.contains(""""tool_calls":[{"id":"call_1","type":"function""""))
        assertFalse(assistant.contains(""""content""""))   // explicitNulls=false：null 不落 wire

        val result = AiWireJson.encodeToString(
            ChatWireMessage.serializer(),
            ChatWireMessage("tool", "购物车是空的", toolCallId = "call_1", name = "get_cart"),
        )
        assertTrue(result.contains(""""tool_call_id":"call_1""""))
        assertTrue(result.contains(""""name":"get_cart""""))
    }

    @Test
    fun `mutation tools are exactly the five write operations`() {
        val mutations = AiToolName.entries.filter { it.kind == AiToolName.Kind.MUTATION }
        assertEquals(
            setOf(
                AiToolName.ADD_TO_CART,
                AiToolName.PLACE_ORDER,
                AiToolName.PROPOSE_ADJUSTMENT,
                AiToolName.CREATE_CATEGORY_SCHEME,
                AiToolName.ASSIGN_CATEGORY_PRODUCTS,
            ),
            mutations.toSet(),
        )
    }

    @Test
    fun `identity gating is mutually exclusive by domain`() {
        // 产品语义：工具可用集是当前身份的函数，三域互斥 ——
        // 非买家身份不能加购/下单；get_order_detail 是唯一跨域例外（consumer+agent）。
        val consumer = AiToolName.entries.filter { it.allowedFor("consumer") }.toSet()
        assertEquals(
            setOf(
                AiToolName.SEARCH_PRODUCTS, AiToolName.GET_PRODUCT_DETAIL, AiToolName.GET_CATEGORIES,
                AiToolName.ADD_TO_CART, AiToolName.GET_CART, AiToolName.PLACE_ORDER,
                AiToolName.LIST_MY_ORDERS, AiToolName.GET_ORDER_DETAIL,
            ),
            consumer,
        )

        val agent = AiToolName.entries.filter { it.allowedFor("agent") }.toSet()
        assertEquals(
            setOf(
                AiToolName.LIST_MY_CLAIMS, AiToolName.BUILD_PURCHASE_LIST,
                AiToolName.SUMMARIZE_SETTLEMENTS, AiToolName.PROPOSE_ADJUSTMENT,
                AiToolName.GET_ORDER_DETAIL,
            ),
            agent,
        )
        assertFalse(AiToolName.ADD_TO_CART.allowedFor("agent"))
        assertFalse(AiToolName.PLACE_ORDER.allowedFor("agent"))

        val merchant = AiToolName.entries.filter { it.allowedFor("merchant") }.toSet()
        assertEquals(
            setOf(
                AiToolName.LIST_STORE_PRODUCTS, AiToolName.LIST_CATEGORY_SCHEMES,
                AiToolName.CREATE_CATEGORY_SCHEME, AiToolName.ASSIGN_CATEGORY_PRODUCTS,
            ),
            merchant,
        )
        assertFalse(AiToolName.ADD_TO_CART.allowedFor("merchant"))
    }

    @Test
    fun `store context carries structural merchant scope`() {
        // 红线：进店 ✨ 的本店限定必须是结构化 scope（代码兑现），不是 seedNote 散文
        val ctx = AiContext.store(62, "Demo Store")
        assertEquals(62, ctx.scope.merchantId)
        assertEquals("Demo Store", ctx.scope.merchantName)
        assertEquals("store:62", ctx.contextKey)
        assertEquals(null, ctx.tools)   // 全量消费者工具（registry 再与身份可用集取交）
    }
}
