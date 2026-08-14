package com.chunland.app.core.ai.storage

import com.chunland.app.core.ai.domain.MediaRef
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 媒体存储（内容寻址，对齐 iOS MediaStore.swift）。
 *
 * 红线：**图片字节永远不进数据库、也永远不进消息**。
 *
 * 旧实现把图片编成 base64 文本塞进消息里，跟着会话 blob 一起被全量读进内存 ——
 * 一张 1MB 照片编码后约 1.37MB，几十条带图会话就是几十 MB 常驻。
 * 这里改成：字节落文件、按内容哈希命名、消息里只留一个引用。
 *
 * 内容寻址顺带解决去重：同一张图在不同会话里发多次，磁盘上只有一份。
 */
class MediaStore(
    private val db: AiDatabase,
    private val mediaDir: File,
) {

    /** 从 MediaRef 还原出磁盘位置 */
    fun fileFor(ref: MediaRef): File = File(mediaDir, ref.relPath)

    // MARK: - 写入

    /**
     * 保存一段媒体字节，返回可放进消息的引用。
     *
     * 同内容重复调用只落一次盘、只写一行 —— 直接返回已有引用。
     * [width] / [height] 由调用方传入：解码图片是 UI 层的事，
     * 存储层不做解码以保持职责单一。
     */
    suspend fun save(
        data: ByteArray,
        mime: String,
        width: Int? = null,
        height: Int? = null,
    ): MediaRef {
        val sha = sha256(data)

        find(sha256 = sha)?.let { existing ->
            // 行在但文件被系统清掉了（极少数情况）→ 补回文件，引用不变
            val f = fileFor(existing)
            if (!f.exists()) writeFile(data, f)
            return existing
        }

        val relPath = relativePath(sha, mime)
        writeFile(data, File(mediaDir, relPath))

        val ref = MediaRef(
            id = UUID.randomUUID().toString(),
            sha256 = sha,
            relPath = relPath,
            mime = mime,
            bytes = data.size,
            width = width,
            height = height,
        )
        db.execute(
            """
            INSERT INTO media (id, sha256, rel_path, mime, bytes, width, height, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                SqlValue.text(ref.id), SqlValue.text(sha), SqlValue.text(relPath),
                SqlValue.text(mime), SqlValue.int(ref.bytes),
                SqlValue.of(width), SqlValue.of(height),
                SqlValue.long(System.currentTimeMillis()),
            )
        )
        return ref
    }

    // MARK: - 读取

    suspend fun find(sha256: String): MediaRef? =
        db.query("SELECT * FROM media WHERE sha256 = ? LIMIT 1;", listOf(SqlValue.text(sha256)))
            .firstOrNull()?.let(::decode)

    suspend fun findById(id: String): MediaRef? =
        db.query("SELECT * FROM media WHERE id = ? LIMIT 1;", listOf(SqlValue.text(id)))
            .firstOrNull()?.let(::decode)

    /** 批量取（消息加载时一次性拿齐，避免逐条查） */
    suspend fun find(ids: List<String>): Map<String, MediaRef> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return db.query(
            "SELECT * FROM media WHERE id IN ($placeholders);",
            ids.map { SqlValue.text(it) }
        ).mapNotNull(::decode).associateBy { it.id }
    }

    /** 读回字节（wire 编码时才调用 —— 只在编码那一刻把字节读进内存，用完即弃） */
    fun loadBytes(ref: MediaRef): ByteArray = fileFor(ref).readBytes()

    // MARK: - 回收
    //
    // 删会话时不立即删文件：同一张图可能被别的会话引用（内容寻址去重的代价）。
    // 改为低频 GC —— 扫出没有任何 part 引用的 media 行，删行删文件。
    // 由 App 启动后台调用，失败无所谓，下次再扫。

    suspend fun collectGarbage(): Int {
        val rows = db.query(
            """
            SELECT m.id, m.rel_path FROM media m
            WHERE NOT EXISTS (SELECT 1 FROM message_parts p WHERE p.media_id = m.id)
            """.trimIndent()
        )
        if (rows.isEmpty()) return 0

        rows.forEach { row ->
            val id = row.string("id") ?: return@forEach
            val rel = row.string("rel_path") ?: return@forEach
            runCatching { File(mediaDir, rel).delete() }
            db.execute("DELETE FROM media WHERE id = ?;", listOf(SqlValue.text(id)))
        }
        return rows.size
    }

    // MARK: - 内部

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    /** `ab/abcdef….jpg` —— 前两位分桶，避免单目录堆几千个文件 */
    private fun relativePath(sha256: String, mime: String): String =
        "${sha256.take(2)}/$sha256.${fileExtension(mime)}"

    private fun fileExtension(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        else -> "bin"
    }

    private fun writeFile(data: ByteArray, file: File) {
        file.parentFile?.mkdirs()
        // 先写临时文件再改名 —— 避免写一半被打断留下半张图
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun decode(row: SqlRow): MediaRef? {
        val id = row.string("id") ?: return null
        val sha = row.string("sha256") ?: return null
        val rel = row.string("rel_path") ?: return null
        val mime = row.string("mime") ?: return null
        val bytes = row.int("bytes") ?: return null
        return MediaRef(
            id = id, sha256 = sha, relPath = rel, mime = mime, bytes = bytes,
            width = row.int("width"), height = row.int("height"),
        )
    }
}
