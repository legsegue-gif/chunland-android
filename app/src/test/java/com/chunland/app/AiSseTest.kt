package com.chunland.app

import com.chunland.app.core.ai.SseEvent
import com.chunland.app.core.ai.parseSsePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁 OpenAI 兼容 SSE 解析契约（样例即真实 chunk 形态，对齐 iOS StreamChunk 的兼容面：
 * content delta / reasoning 双字段名 / finish_reason / event:error 帧）。
 */
class AiSseTest {

    @Test
    fun `standard content delta`() {
        val ev = parseSsePayload(
            """{"id":"c1","choices":[{"index":0,"delta":{"content":"加"},"finish_reason":null}]}""",
            isErrorEvent = false,
        )
        assertTrue(ev is SseEvent.Delta)
        assertEquals("加", (ev as SseEvent.Delta).content)
        assertNull(ev.reasoning)
        assertNull(ev.finishReason)
    }

    @Test
    fun `reasoning_content variant (DeepSeek standard)`() {
        val ev = parseSsePayload(
            """{"choices":[{"delta":{"reasoning_content":"思考中"},"finish_reason":null}]}""",
            isErrorEvent = false,
        )
        assertEquals("思考中", (ev as SseEvent.Delta).reasoning)
    }

    @Test
    fun `reasoning variant (adapter flavor)`() {
        val ev = parseSsePayload(
            """{"choices":[{"delta":{"reasoning":"推理"}}]}""",
            isErrorEvent = false,
        )
        assertEquals("推理", (ev as SseEvent.Delta).reasoning)
    }

    @Test
    fun `finish_reason stop with empty delta`() {
        val ev = parseSsePayload(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            isErrorEvent = false,
        )
        assertEquals("stop", (ev as SseEvent.Delta).finishReason)
        assertNull(ev.content)
    }

    @Test
    fun `error event with nested detail`() {
        val ev = parseSsePayload(
            """{"error":{"message":"model not loaded"}}""",
            isErrorEvent = true,
        )
        assertEquals("model not loaded", (ev as SseEvent.Error).message)
    }

    @Test
    fun `error event with flat message`() {
        val ev = parseSsePayload("""{"message":"boom"}""", isErrorEvent = true)
        assertEquals("boom", (ev as SseEvent.Error).message)
    }

    @Test
    fun `error event with unparseable payload falls back to raw text`() {
        val ev = parseSsePayload("plain text failure", isErrorEvent = true)
        assertEquals("plain text failure", (ev as SseEvent.Error).message)
    }

    @Test
    fun `unparseable or empty chunks are skipped`() {
        assertTrue(parseSsePayload("not json", isErrorEvent = false) is SseEvent.Skip)
        assertTrue(parseSsePayload("""{"choices":[]}""", isErrorEvent = false) is SseEvent.Skip)
        assertTrue(
            parseSsePayload("""{"choices":[{"delta":{}}]}""", isErrorEvent = false) is SseEvent.Skip,
        )
    }

    // ---- tool_calls 增量（标准 OpenAI：首片带 id+name，后续只有 arguments 切片） ----

    @Test
    fun `tool_calls head delta carries id and name`() {
        val ev = parseSsePayload(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_cart","arguments":""}}]},"finish_reason":null}]}""",
            isErrorEvent = false,
        )
        val d = ev as SseEvent.Delta
        val tc = d.toolCalls!!.single()
        assertEquals(0, tc.index)
        assertEquals("call_1", tc.id)
        assertEquals("get_cart", tc.function?.name)
    }

    @Test
    fun `tool_calls arguments-only delta keeps index`() {
        val ev = parseSsePayload(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"qu"}}]}}]}""",
            isErrorEvent = false,
        )
        val tc = (ev as SseEvent.Delta).toolCalls!!.single()
        assertEquals(0, tc.index)
        assertNull(tc.id)
        assertEquals("{\"qu", tc.function?.arguments)
    }

    @Test
    fun `finish_reason tool_calls with empty delta`() {
        val ev = parseSsePayload(
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            isErrorEvent = false,
        )
        assertEquals("tool_calls", (ev as SseEvent.Delta).finishReason)
        assertNull(ev.toolCalls)
    }
}
