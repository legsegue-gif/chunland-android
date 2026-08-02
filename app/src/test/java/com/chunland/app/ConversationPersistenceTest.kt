package com.chunland.app

import com.chunland.app.core.ai.StoredConversation
import com.chunland.app.core.ai.StoredMessage
import com.chunland.app.core.ai.WireToolCall
import com.chunland.app.core.ai.WireToolCallFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlinx.serialization.json.Json

/**
 * 锁会话落盘往返契约（与 ConversationStore 同一 Json 配置）：
 * 恢复后 assistant 的 tool_calls / tool 结果的 callId 必须完整存活 —— 否则历史回发
 * 缺工具调用。属主字段是隔离真相源，也一并锁住。
 */
class ConversationPersistenceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `round-trip preserves tool call wire shape`() {
        val original = StoredConversation(
            id = "c1",
            ownerUserId = "55",
            title = "买点零食",
            contextKey = "store:62",
            updatedAt = 1_700_000_000_000L,
            messages = listOf(
                StoredMessage(role = "user", content = "有什么可以买"),
                StoredMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        WireToolCall(
                            id = "call_abc",
                            function = WireToolCallFunction(
                                name = "search_products",
                                arguments = """{"keyword":""}""",
                            ),
                        ),
                    ),
                ),
                StoredMessage(
                    role = "tool",
                    content = """{"items":[]}""",
                    toolCallId = "call_abc",
                    toolName = "search_products",
                ),
                StoredMessage(role = "assistant", content = "这家店暂时没有商品"),
            ),
        )

        val blob = json.encodeToString(StoredConversation.serializer(), original)
        val restored = json.decodeFromString(StoredConversation.serializer(), blob)

        assertEquals(original, restored)

        // 显式锁工具链关键字段（对象相等已覆盖，但这些是历史回发的命脉，单独断言防未来误改）
        val toolCallMsg = restored.messages[1]
        assertNotNull(toolCallMsg.toolCalls)
        assertEquals("call_abc", toolCallMsg.toolCalls!!.single().id)
        assertEquals("search_products", toolCallMsg.toolCalls.single().function.name)

        val toolResult = restored.messages[2]
        assertEquals("call_abc", toolResult.toolCallId)
        assertEquals("search_products", toolResult.toolName)
    }

    @Test
    fun `owner id survives round-trip for isolation`() {
        val conv = StoredConversation(
            id = "c2",
            ownerUserId = "guest",
            title = "对话",
            updatedAt = 1L,
            messages = listOf(StoredMessage(role = "user", content = "hi")),
        )
        val restored = json.decodeFromString(
            StoredConversation.serializer(),
            json.encodeToString(StoredConversation.serializer(), conv),
        )
        assertEquals("guest", restored.ownerUserId)
        assertEquals(null, restored.contextKey)
    }
}
