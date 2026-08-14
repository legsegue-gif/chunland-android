package com.chunland.app.core.ai.storage

import java.util.UUID

/**
 * 会话元数据（对齐 iOS AISessionRecord）。
 *
 * 注意这里**没有消息** —— 会话列表只需要这些字段。
 * messageCount 与 lastPreview 是表里的冗余列，正是为了让列表不必碰消息表：
 * 旧实现每次刷新都把所有会话的所有消息读进内存常驻，
 * 这两列 + 分页就是为了根除那个模式。
 */
data class AiSessionRecord(
    val id: String = UUID.randomUUID().toString(),
    val ownerUserId: String?,
    val title: String,
    /** 页面 ✨ 会话的续聊键（如 `product:123`）；主会话为 null */
    val contextKey: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val lastPreview: String? = null,
) {
    /** 还没聊过 —— 关闭页面 ✨ 时据此判断要不要直接删掉，避免抽屉堆一次性死会话 */
    val isEmpty: Boolean get() = messageCount == 0
}

/** 跨会话搜索命中 */
data class AiSearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val messageId: String,
    val updatedAt: Long,
    /** 已截取的原文片段（高亮由 UI 层用 AiTextSegmenter.highlightRanges 定位） */
    val snippet: String,
)

/**
 * 会话仓库（对齐 iOS SessionRepo.swift）。
 *
 * ⚠️ 所有读路径都按 owner_user_id 过滤 —— 同设备换账号绝不能看到别人的历史。
 * SQL 里用 `IS ?` 而不是 `= ?`，这样 NULL（游客）也能正确匹配。
 */
class SessionRepo(private val db: AiDatabase) {

    companion object {
        /** 新会话默认标题。首条用户消息落库后由调用方替换 */
        const val UNTITLED = "新对话"
    }

    // MARK: - 读

    /** 会话列表，最近在前。**只查 sessions 表** */
    suspend fun list(owner: String?, limit: Int = 30, offset: Int = 0): List<AiSessionRecord> =
        db.query(
            """
            SELECT * FROM sessions
            WHERE owner_user_id IS ?
            ORDER BY updated_at DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            listOf(SqlValue.of(owner), SqlValue.int(limit), SqlValue.int(offset))
        ).mapNotNull(::decode)

    suspend fun count(owner: String?): Int =
        db.query(
            "SELECT COUNT(*) AS n FROM sessions WHERE owner_user_id IS ?;",
            listOf(SqlValue.of(owner))
        ).firstOrNull()?.int("n") ?: 0

    suspend fun find(id: String): AiSessionRecord? =
        db.query("SELECT * FROM sessions WHERE id = ? LIMIT 1;", listOf(SqlValue.text(id)))
            .firstOrNull()?.let(::decode)

    /**
     * 页面 ✨ 续聊：同 contextKey 且在时间窗内的最近一条。
     * 走 `idx_sessions_context` 一次索引查找。
     */
    /**
     * 同 contextKey 的近期会话。
     *
     * [withinMillis] 为 null = **不限时间**，永远复用那一条。这不是「窗口很大」——
     * 用一个巨大的数值会让 `now - within` 下溢，反而一条都匹配不上。
     * 页面 ✨ 用有限窗口（问一半收起再打开要能接上，但隔天该是新话题）；
     * 助手 tab 的主对话用 null（它就是「你正在进行的那个对话」，
     * 换新的是用户按「新对话」的显式动作）。
     */
    suspend fun recent(owner: String?, contextKey: String, withinMillis: Long?): AiSessionRecord? {
        val binds = mutableListOf(SqlValue.of(owner), SqlValue.text(contextKey))
        val timeFilter = if (withinMillis == null) {
            ""
        } else {
            binds += SqlValue.long(System.currentTimeMillis() - withinMillis)
            " AND updated_at >= ?"
        }
        return db.query(
            """
            SELECT * FROM sessions
            WHERE owner_user_id IS ? AND context_key = ?$timeFilter
            ORDER BY updated_at DESC
            LIMIT 1
            """.trimIndent(),
            binds,
        ).firstOrNull()?.let(::decode)
    }

    // MARK: - 写

    suspend fun create(
        owner: String?,
        title: String = UNTITLED,
        contextKey: String? = null,
    ): AiSessionRecord {
        val record = AiSessionRecord(ownerUserId = owner, title = title, contextKey = contextKey)
        db.execute(
            """
            INSERT INTO sessions (id, owner_user_id, title, context_key,
                                  created_at, updated_at, message_count, last_preview)
            VALUES (?, ?, ?, ?, ?, ?, 0, NULL)
            """.trimIndent(),
            listOf(
                SqlValue.text(record.id), SqlValue.of(owner), SqlValue.text(title),
                SqlValue.of(contextKey), SqlValue.long(record.createdAt),
                SqlValue.long(record.updatedAt),
            )
        )
        return record
    }

    suspend fun rename(id: String, title: String) {
        db.execute(
            "UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?;",
            listOf(SqlValue.text(title), SqlValue.long(System.currentTimeMillis()), SqlValue.text(id))
        )
    }

    /**
     * 摘掉续聊键 —— 页面 ✨ 的「新对话」是归档语义：
     * 旧会话留在抽屉里当历史，只是不再被续聊命中，新会话独占这个 key。
     */
    suspend fun detachContextKey(id: String) {
        db.execute("UPDATE sessions SET context_key = NULL WHERE id = ?;", listOf(SqlValue.text(id)))
    }

    suspend fun delete(id: String) {
        // messages / message_parts / offloads 由外键 CASCADE 带走；
        // media 不在此处删（可能被别的会话引用），留给 MediaStore 的 GC；
        // part_search 的外键挂在 message_parts 上，这里删的是 sessions，
        // 级联链断在中间，必须显式删 —— 与主表删除放同一事务。
        db.transaction { tx ->
            tx.execute("DELETE FROM part_search WHERE session_id = ?;", listOf(SqlValue.text(id)))
            tx.execute("DELETE FROM sessions WHERE id = ?;", listOf(SqlValue.text(id)))
        }
    }

    suspend fun deleteAll(owner: String?) {
        db.transaction { tx ->
            tx.execute(
                """
                DELETE FROM part_search WHERE session_id IN
                  (SELECT id FROM sessions WHERE owner_user_id IS ?)
                """.trimIndent(),
                listOf(SqlValue.of(owner))
            )
            tx.execute(
                "DELETE FROM sessions WHERE owner_user_id IS ?;",
                listOf(SqlValue.of(owner))
            )
        }
    }

    /**
     * 收编无主会话 —— 加属主列之前建的行归本机首个登录用户。
     * 单人设备（绝大多数）无感保留历史；共享设备上归先登录者，可接受。
     */
    suspend fun adoptOrphans(owner: String): Int =
        db.execute(
            "UPDATE sessions SET owner_user_id = ? WHERE owner_user_id IS NULL;",
            listOf(SqlValue.text(owner))
        )

    // MARK: - 搜索

    suspend fun search(owner: String?, keyword: String, limit: Int = 50): List<AiSearchHit> {
        val patterns = AiTextSegmenter.likePatterns(keyword)
        if (patterns.isEmpty()) return emptyList()

        // 多个词是 AND —— 每个词一条 LIKE 条件
        val conditions = patterns.joinToString(" AND ") {
            "f.seg LIKE ? ESCAPE '${AiTextSegmenter.LIKE_ESCAPE}'"
        }

        // 取回的是 message_parts 里的**原文**，不是索引里的分词文本 ——
        // 分词文本满是空格，拿去显示很难看，还原又会吃掉用户自己打的空格。
        // 高亮由 UI 层用 AiTextSegmenter.highlightRanges 在原文里定位。
        return db.query(
            """
            SELECT s.id         AS session_id,
                   s.title      AS session_title,
                   s.updated_at AS updated_at,
                   f.message_id AS message_id,
                   p.text       AS raw_text
            FROM part_search f
            JOIN sessions      s ON s.id = f.session_id
            JOIN message_parts p ON p.id = f.part_id
            WHERE $conditions AND s.owner_user_id IS ?
            ORDER BY s.updated_at DESC
            LIMIT ?
            """.trimIndent(),
            patterns.map { SqlValue.text(it) } + listOf(SqlValue.of(owner), SqlValue.int(limit))
        ).mapNotNull { row ->
            val sid = row.string("session_id") ?: return@mapNotNull null
            val mid = row.string("message_id") ?: return@mapNotNull null
            AiSearchHit(
                sessionId = sid,
                sessionTitle = row.string("session_title") ?: "",
                messageId = mid,
                updatedAt = row.epochMillis("updated_at") ?: System.currentTimeMillis(),
                snippet = AiTextSegmenter.excerpt(row.string("raw_text") ?: "", keyword),
            )
        }
    }

    // MARK: - 内部

    private fun decode(row: SqlRow): AiSessionRecord? {
        val id = row.string("id") ?: return null
        val title = row.string("title") ?: return null
        return AiSessionRecord(
            id = id,
            ownerUserId = row.string("owner_user_id"),
            title = title,
            contextKey = row.string("context_key"),
            createdAt = row.epochMillis("created_at") ?: 0L,
            updatedAt = row.epochMillis("updated_at") ?: 0L,
            messageCount = row.int("message_count") ?: 0,
            lastPreview = row.string("last_preview"),
        )
    }
}
