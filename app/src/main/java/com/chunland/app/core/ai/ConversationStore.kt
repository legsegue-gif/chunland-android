package com.chunland.app.core.ai

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// 落盘 DTO（对齐 iOS StoredMessage 思路：messages 序列化 blob，wire 形态原样保存 ——
// assistant 的 toolCalls / tool 结果的 callId 必须完整，否则恢复后历史回发缺工具调用）。

@Serializable
data class StoredMessage(
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val note: String? = null,
    val toolCalls: List<WireToolCall>? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
)

@Serializable
data class StoredConversation(
    val id: String,
    /** 属主隔离（红线：绝不外泄他人历史）；游客 = "guest" 独立桶 */
    val ownerUserId: String,
    val title: String,
    /** scoped ✨ 续聊 key（如 "store:62"）；tab 主会话 null */
    val contextKey: String? = null,
    val updatedAt: Long,
    val messages: List<StoredMessage> = emptyList(),
)

/**
 * AI 多会话持久化（对齐 iOS SwiftData 多会话的语义，实现刻意走文件 JSON）：
 * iOS 的 SwiftData 本质就是「会话行 + messages 序列化 blob」；Android 若用 Room 需引
 * KSP 编译器插件且与 Kotlin 版本强耦合（依赖纪律：不单点乱升），而会话量级
 * 只有几十，`files/ai-conversations/<id>.json` 每会话一文件零新依赖等价达成。
 *
 * 属主语义：读路径一律按 ownerUserId 过滤（换账号绝不见他人历史）；登出不删盘
 * （数据仍按属主隔离，重新登录可续）。所有 IO 走 Dispatchers.IO。
 */
class ConversationStore(context: Context) {

    private val dir = File(context.filesDir, "ai-conversations")

    // 落盘格式独立于 wire：encodeDefaults 防默认值瘦身后老文件缺键，ignoreUnknownKeys 向前兼容
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun save(conversation: StoredConversation) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            File(dir, "${conversation.id}.json")
                .writeText(json.encodeToString(StoredConversation.serializer(), conversation))
        }
        Unit
    }

    /** 属主的全部会话，新→旧。坏文件跳过不炸（持久化永远不能挡对话）。 */
    suspend fun list(ownerUserId: String): List<StoredConversation> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f ->
                runCatching {
                    json.decodeFromString(StoredConversation.serializer(), f.readText())
                }.getOrNull()
            }
            .filter { it.ownerUserId == ownerUserId }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        runCatching { File(dir, "$id.json").delete() }
        Unit
    }

    /** scoped ✨ 续聊：同 contextKey 且 maxAgeMs 内最新的一条（对齐 iOS 24h 复用语义）。 */
    suspend fun latestByContextKey(
        ownerUserId: String,
        contextKey: String,
        maxAgeMs: Long,
    ): StoredConversation? {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        return list(ownerUserId).firstOrNull { it.contextKey == contextKey && it.updatedAt >= cutoff }
    }
}
