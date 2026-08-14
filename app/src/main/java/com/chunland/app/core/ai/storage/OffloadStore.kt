package com.chunland.app.core.ai.storage

import java.util.UUID

/**
 * 上下文卸载存储（对齐 iOS OffloadStore.swift）。
 *
 * 上下文吃紧时，把历史里的大工具结果从消息中抽走：正文换成一句占位说明，
 * 原文存到这里，模型需要重看时按 ref 取回。
 *
 * 这是对「卸载到文件系统」的变形 —— 我们不给模型文件系统，
 * 所以落到表里由代码按 ref 供给，效果等价。
 */
class OffloadStore(private val db: AiDatabase) {

    companion object {
        /** 占位文本的前缀。已经卸载过的片段不再重复卸载，靠它识别 */
        const val PLACEHOLDER_PREFIX = "[内容已转存]"

        /**
         * 生成放回消息里的占位说明。
         *
         * 要写清三件事：内容去哪了、有多大、怎么取回 —— 否则模型只知道「没了」，
         * 会倾向于重新调用一遍工具（那正是卸载想省掉的开销）。
         */
        fun placeholder(ref: String, toolName: String?, bytes: Int): String {
            val who = toolName?.let { "$it 的" } ?: ""
            return "$PLACEHOLDER_PREFIX${who}完整结果（约 $bytes 字节）已从当前上下文移出，" +
                "引用标识 `$ref`。若确需重看完整内容，用该标识取回；" +
                "多数情况下依据下文已有的摘要继续即可。"
        }
    }

    // MARK: - 写入

    /** 卸载一段内容，返回引用标识 */
    suspend fun offload(sessionId: String, toolName: String?, content: String): String {
        val ref = "off_" + UUID.randomUUID().toString().replace("-", "").take(12)
        db.execute(
            """
            INSERT INTO offloads (ref, session_id, tool_name, content, bytes, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                SqlValue.text(ref), SqlValue.text(sessionId), SqlValue.of(toolName),
                SqlValue.text(content), SqlValue.int(content.toByteArray().size),
                SqlValue.long(System.currentTimeMillis()),
            )
        )
        return ref
    }

    // MARK: - 读取

    suspend fun content(ref: String): String? =
        db.query("SELECT content FROM offloads WHERE ref = ? LIMIT 1;", listOf(SqlValue.text(ref)))
            .firstOrNull()?.string("content")

    /** 某会话已卸载的总字节数（诊断用） */
    suspend fun totalBytes(sessionId: String): Int =
        db.query(
            "SELECT COALESCE(SUM(bytes), 0) AS total FROM offloads WHERE session_id = ?;",
            listOf(SqlValue.text(sessionId))
        ).firstOrNull()?.int("total") ?: 0

    // MARK: - 清理
    //
    // 会话删除时由外键 CASCADE 自动清掉，无需手动调用。
    // 这里只提供单会话清空（清空对话但保留会话本身时用）。

    suspend fun clear(sessionId: String) {
        db.execute("DELETE FROM offloads WHERE session_id = ?;", listOf(SqlValue.text(sessionId)))
    }
}
