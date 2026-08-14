package com.chunland.app.core.ai.storage

import com.chunland.app.core.ai.domain.AgentContentPart
import com.chunland.app.core.ai.domain.AgentMessage
import com.chunland.app.core.ai.domain.AgentToolInput
import com.chunland.app.core.ai.domain.MediaRef
import java.util.UUID

/**
 * 消息仓库（domain ↔ storage 映射所在，对齐 iOS MessageRepo.swift）。
 *
 * 写入是 append-only：消息一旦落库就不再改，唯一的例外是卸载时替换某个片段的正文。
 * 流式生成期间消息只在内存里，一轮结束时（assistant 消息 + 全部工具结果）
 * 在**同一个事务**里落库 —— 半条历史比没有历史更糟，配对关系不能跨事务断开。
 */
class MessageRepo(
    private val db: AiDatabase,
    private val media: MediaStore,
) {

    companion object {
        /** 会话列表预览取多少字 */
        private const val PREVIEW_LENGTH = 60

        private const val INSERT_PART_SQL = """
            INSERT INTO message_parts
              (id, message_id, idx, kind, text, tool_use_id, tool_name, tool_input, is_error, media_id, offload_ref)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    }

    // MARK: - 写

    /**
     * 追加一批消息，并在同一事务里更新会话的冗余列。
     *
     * 返回落库后带上 `dbId` 的消息（调用方要回填到内存历史里 ——
     * 压缩与卸载按 id 定位消息边界，不能用列表下标，历史随时会被裁剪）。
     */
    suspend fun append(sessionId: String, messages: List<AgentMessage>): List<AgentMessage> {
        if (messages.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()

        return db.transaction { tx ->
            var seq = nextSeq(tx, sessionId)
            val stored = messages.map { msg ->
                val messageId = msg.dbId ?: UUID.randomUUID().toString()

                tx.execute(
                    """
                    INSERT INTO messages (id, session_id, seq, role, interrupted, reasoning, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    listOf(
                        SqlValue.text(messageId), SqlValue.text(sessionId), SqlValue.int(seq),
                        SqlValue.text(msg.role.wire), SqlValue.bool(msg.isInterrupted),
                        SqlValue.of(msg.reasoning), SqlValue.long(now),
                    )
                )
                seq += 1

                msg.parts.forEachIndexed { idx, part ->
                    insertPart(tx, sessionId, messageId, idx, part)
                }
                msg.copy(dbId = messageId)
            }

            // 冗余列与主表在同一事务更新，保证列表看到的计数与预览永远和消息一致。
            val preview = stored.lastOrNull()?.let(::preview) ?: ""
            tx.execute(
                """
                UPDATE sessions
                SET message_count = message_count + ?,
                    last_preview  = ?,
                    updated_at    = ?
                WHERE id = ?
                """.trimIndent(),
                listOf(
                    SqlValue.int(stored.size), SqlValue.text(preview),
                    SqlValue.long(now), SqlValue.text(sessionId),
                )
            )
            stored
        }
    }

    /**
     * 卸载：把某个片段的正文换成占位说明，并记下取回用的引用。
     *
     * 只改 text 与 offload_ref 两列 —— 片段的身份（配对键、类型、顺序）绝不动，
     * 否则会破坏「调用与结果严格配对同序」这条第一约束。
     */
    suspend fun markOffloaded(messageId: String, partIndex: Int, placeholder: String, ref: String) {
        db.execute(
            """
            UPDATE message_parts SET text = ?, offload_ref = ?
            WHERE message_id = ? AND idx = ?
            """.trimIndent(),
            listOf(
                SqlValue.text(placeholder), SqlValue.text(ref),
                SqlValue.text(messageId), SqlValue.int(partIndex),
            )
        )
    }

    /**
     * 清空会话的消息（会话本身保留）。
     *
     * FTS 不是 external content，外键 CASCADE 带不走它，必须显式删 ——
     * 漏了会留下一批指向已删消息的索引行，搜索结果点进去是空的。
     */
    suspend fun deleteAll(sessionId: String) {
        db.transaction { tx ->
            tx.execute("DELETE FROM part_search WHERE session_id = ?;", listOf(SqlValue.text(sessionId)))
            tx.execute("DELETE FROM messages WHERE session_id = ?;", listOf(SqlValue.text(sessionId)))
            tx.execute(
                "UPDATE sessions SET message_count = 0, last_preview = NULL WHERE id = ?;",
                listOf(SqlValue.text(sessionId))
            )
        }
    }

    // MARK: - 读

    /**
     * 加载会话的最近 [limit] 条消息，按时间正序返回。
     *
     * [beforeSeq] 用于上滑加载更早：传入当前最早一条的 seq。
     */
    suspend fun load(
        sessionId: String,
        limit: Int = 100,
        beforeSeq: Int? = null,
    ): List<AgentMessage> {
        val messageRows = if (beforeSeq != null) {
            db.query(
                """
                SELECT * FROM messages WHERE session_id = ? AND seq < ?
                ORDER BY seq DESC LIMIT ?
                """.trimIndent(),
                listOf(SqlValue.text(sessionId), SqlValue.int(beforeSeq), SqlValue.int(limit))
            )
        } else {
            db.query(
                "SELECT * FROM messages WHERE session_id = ? ORDER BY seq DESC LIMIT ?;",
                listOf(SqlValue.text(sessionId), SqlValue.int(limit))
            )
        }
        if (messageRows.isEmpty()) return emptyList()

        val ordered = messageRows.reversed()
        val ids = ordered.mapNotNull { it.string("id") }
        val placeholders = ids.joinToString(",") { "?" }

        val partRows = db.query(
            """
            SELECT * FROM message_parts WHERE message_id IN ($placeholders)
            ORDER BY message_id, idx
            """.trimIndent(),
            ids.map { SqlValue.text(it) }
        )

        // 媒体一次性批量取，避免每个片段查一次库。
        val mediaIds = partRows.mapNotNull { it.string("media_id") }.distinct()
        val mediaMap = media.find(mediaIds)

        val partsByMessage = mutableMapOf<String, MutableList<AgentContentPart>>()
        partRows.forEach { row ->
            val mid = row.string("message_id") ?: return@forEach
            val part = decodePart(row, mediaMap) ?: return@forEach
            partsByMessage.getOrPut(mid) { mutableListOf() } += part
        }

        return ordered.mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            val role = row.string("role")?.let(AgentMessage.Role::from) ?: return@mapNotNull null
            AgentMessage(
                role = role,
                parts = partsByMessage[id] ?: emptyList(),
                isInterrupted = row.bool("interrupted") ?: false,
                reasoning = row.string("reasoning"),
                dbId = id,
            )
        }
    }

    /** 当前最小 seq —— 上滑分页的游标 */
    suspend fun earliestSeq(sessionId: String): Int? =
        db.query(
            "SELECT MIN(seq) AS s FROM messages WHERE session_id = ?;",
            listOf(SqlValue.text(sessionId))
        ).firstOrNull()?.int("s")

    // MARK: - 内部

    private fun nextSeq(tx: AiDatabase.Tx, sessionId: String): Int =
        (tx.query(
            "SELECT COALESCE(MAX(seq), -1) AS s FROM messages WHERE session_id = ?;",
            listOf(SqlValue.text(sessionId))
        ).firstOrNull()?.int("s") ?: -1) + 1

    private fun insertPart(
        tx: AiDatabase.Tx,
        sessionId: String,
        messageId: String,
        idx: Int,
        part: AgentContentPart,
    ) {
        val partId = UUID.randomUUID().toString()
        val sql = INSERT_PART_SQL.trimIndent()

        when (part) {
            is AgentContentPart.Text -> {
                tx.execute(
                    sql,
                    listOf(
                        SqlValue.text(partId), SqlValue.text(messageId), SqlValue.int(idx),
                        SqlValue.text(AiSchema.PartKind.TEXT.wire), SqlValue.text(part.text),
                        SqlValue.Null, SqlValue.Null, SqlValue.Null,
                        SqlValue.Null, SqlValue.Null, SqlValue.Null,
                    )
                )
                // 只有正文进检索索引。分词无法在 SQL 触发器里做，
                // 所以 FTS 由这里与消息写入在同一事务内维护 —— 两者绝不会不一致。
                val seg = AiTextSegmenter.segment(part.text)
                if (seg.isNotEmpty()) {
                    tx.execute(
                        """
                        INSERT INTO part_search (part_id, message_id, session_id, seg)
                        VALUES (?, ?, ?, ?)
                        """.trimIndent(),
                        listOf(
                            SqlValue.text(partId), SqlValue.text(messageId),
                            SqlValue.text(sessionId), SqlValue.text(seg),
                        )
                    )
                }
            }

            is AgentContentPart.ToolUse -> tx.execute(
                sql,
                listOf(
                    SqlValue.text(partId), SqlValue.text(messageId), SqlValue.int(idx),
                    SqlValue.text(AiSchema.PartKind.TOOL_USE.wire), SqlValue.Null,
                    SqlValue.text(part.id), SqlValue.text(part.name),
                    SqlValue.text(part.input.jsonString()),
                    SqlValue.Null, SqlValue.Null, SqlValue.Null,
                )
            )

            is AgentContentPart.ToolResult -> tx.execute(
                sql,
                listOf(
                    SqlValue.text(partId), SqlValue.text(messageId), SqlValue.int(idx),
                    SqlValue.text(AiSchema.PartKind.TOOL_RESULT.wire), SqlValue.text(part.text),
                    SqlValue.text(part.id), SqlValue.text(part.name), SqlValue.Null,
                    SqlValue.bool(part.isError), SqlValue.of(part.media?.id),
                    SqlValue.of(part.offloadRef),
                )
            )

            is AgentContentPart.Image -> tx.execute(
                sql,
                listOf(
                    SqlValue.text(partId), SqlValue.text(messageId), SqlValue.int(idx),
                    SqlValue.text(AiSchema.PartKind.IMAGE.wire), SqlValue.Null,
                    SqlValue.Null, SqlValue.Null, SqlValue.Null, SqlValue.Null,
                    SqlValue.text(part.media.id), SqlValue.Null,
                )
            )
        }
    }

    private fun decodePart(row: SqlRow, media: Map<String, MediaRef>): AgentContentPart? {
        val kind = row.string("kind")?.let(AiSchema.PartKind::from) ?: return null
        return when (kind) {
            AiSchema.PartKind.TEXT ->
                AgentContentPart.Text(row.string("text") ?: "")

            AiSchema.PartKind.TOOL_USE -> {
                val id = row.string("tool_use_id") ?: return null
                val name = row.string("tool_name") ?: return null
                AgentContentPart.ToolUse(id, name, AgentToolInput.parse(row.string("tool_input") ?: "{}"))
            }

            AiSchema.PartKind.TOOL_RESULT -> {
                val id = row.string("tool_use_id") ?: return null
                val name = row.string("tool_name") ?: return null
                AgentContentPart.ToolResult(
                    id = id,
                    name = name,
                    text = row.string("text") ?: "",
                    isError = row.bool("is_error") ?: false,
                    media = row.string("media_id")?.let { media[it] },
                    offloadRef = row.string("offload_ref"),
                )
            }

            AiSchema.PartKind.IMAGE -> {
                val mid = row.string("media_id") ?: return null
                media[mid]?.let { AgentContentPart.Image(it) }
            }
        }
    }

    /**
     * 会话列表的预览文本。
     *
     * 只取文本片段：工具调用与结果对用户没有阅读价值，
     * 一条纯工具消息的预览宁可为空，也不要显示一段 JSON。
     */
    private fun preview(message: AgentMessage): String =
        message.plainText.replace('\n', ' ').trim().take(PREVIEW_LENGTH)
}
