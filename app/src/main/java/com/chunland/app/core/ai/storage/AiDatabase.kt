package com.chunland.app.core.ai.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SQLite 连接（零新依赖，直接用框架的 SQLiteOpenHelper）。
 *
 * 为什么不引 Room：Room 需要 KSP 编译器插件（Google 独立发版、滞后于 Kotlin，
 * 升级路径上多一个等待窗口），而我们用到的只是「建表、增删改查、事务、FTS」。
 * 更重要的是**双端同构**：iOS 侧手写 sqlite3 C API，两边写同一套 SQL 才能逐句对照。
 *
 * 写操作全部压到单线程 dispatcher —— 与 iOS 的 actor 语义对齐，
 * 也避免多协程并发写时的锁竞争。
 */
class AiDatabase(context: Context) {

    private val helper = Helper(context.applicationContext)

    /**
     * 单写调度器。`limitedParallelism(1)` 保证所有写串行，
     * 事务期间不会有别的协程插进来写。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** 库文件与媒体目录的根 */
    companion object {
        private const val TAG = "AiDatabase"

        fun rootDirectory(context: Context): File =
            File(context.filesDir, "chunland-ai").apply { mkdirs() }

        fun mediaDirectory(context: Context): File =
            File(rootDirectory(context), "media").apply { mkdirs() }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(
        context, AiSchema.DB_NAME, null, AiSchema.VERSION
    ) {
        init {
            // WAL 由框架管理（读写不互相阻塞：流式写入期间 UI 仍要读会话列表）。
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            // foreign_keys 默认关闭，不显式打开则 CASCADE 静默失效。
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onOpen(db: SQLiteDatabase) {
            AiSchema.PRAGMAS.forEach { pragma ->
                // foreign_keys 已由 onConfigure 处理，重复发无害但没必要
                if (!pragma.contains("foreign_keys")) {
                    runCatching { db.execSQL(pragma) }
                }
            }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.beginTransaction()
            try {
                AiSchema.BOOTSTRAP_STATEMENTS.forEach { db.execSQL(it) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Log.i(TAG, "AI 会话库已初始化 version=${AiSchema.VERSION}")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 只重建检索索引，**会话历史保留** —— 索引可以从消息重算出来，
            // 对话本身不能。整库 drop 虽然更简单，但那是拿用户历史换省事。
            Log.w(TAG, "schema 升级 $oldVersion → $newVersion：重建检索索引")
            db.beginTransaction()
            try {
                db.execSQL("DROP TABLE IF EXISTS parts_fts;")
                db.execSQL("DROP TABLE IF EXISTS part_search;")
                AiSchema.BOOTSTRAP_STATEMENTS.forEach { db.execSQL(it) }
                reindexSearch(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        /**
         * 从 message_parts 重算检索索引。
         *
         * 分词只能在应用层做（SQL 里表达不了），所以升级时逐行读出来重新分词。
         * 消息量级是几千行，一次几十毫秒。
         */
        private fun reindexSearch(db: SQLiteDatabase) {
            db.rawQuery(
                """
                SELECT p.id, p.message_id, m.session_id, p.text
                FROM message_parts p
                JOIN messages m ON m.id = p.message_id
                WHERE p.kind = 'text' AND p.text IS NOT NULL
                """.trimIndent(),
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val seg = AiTextSegmenter.segment(c.getString(3) ?: "")
                    if (seg.isEmpty()) continue
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO part_search (part_id, message_id, session_id, seg)
                        VALUES (?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(c.getString(0), c.getString(1), c.getString(2), seg),
                    )
                }
            }
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 装了旧版 App 打开新库：不删数据，直接报错让上层提示升级。
            throw IllegalStateException("会话库版本 $oldVersion 高于本版本支持的 $newVersion，请升级 App")
        }
    }

    // MARK: - 执行

    /** 执行语句，返回影响行数 */
    suspend fun execute(sql: String, binds: List<SqlValue> = emptyList()): Int =
        withContext(writeDispatcher) { executeBlocking(sql, binds) }

    /** 执行查询 */
    suspend fun query(sql: String, binds: List<SqlValue> = emptyList()): List<SqlRow> =
        withContext(writeDispatcher) { queryBlocking(sql, binds) }

    /**
     * 事务。闭包抛异常则整体回滚。
     *
     * 闭包接收 [Tx]，其方法是**同步**的 —— 事务期间不会挂起，
     * 也就不会有别的协程在事务中间插进来执行 SQL（那条 SQL 会被卷进本事务，
     * 一起提交或一起回滚）。这与 iOS 侧用 `isolated` 参数达到的效果一致。
     *
     * 不支持嵌套 —— 需要嵌套说明职责划分有问题，应由最外层统一开事务。
     */
    suspend fun <T> transaction(body: (Tx) -> T): T = withContext(writeDispatcher) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = body(Tx(this@AiDatabase))
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    /** 事务上下文：只暴露同步方法，杜绝事务中途挂起 */
    class Tx internal constructor(private val db: AiDatabase) {
        fun execute(sql: String, binds: List<SqlValue> = emptyList()): Int =
            db.executeBlocking(sql, binds)

        fun query(sql: String, binds: List<SqlValue> = emptyList()): List<SqlRow> =
            db.queryBlocking(sql, binds)
    }

    // MARK: - 内部（同步实现）

    private fun executeBlocking(sql: String, binds: List<SqlValue>): Int {
        val db = helper.writableDatabase
        val stmt = db.compileStatement(sql)
        try {
            binds.forEachIndexed { i, v -> v.bindTo(stmt, i + 1) }
            return stmt.executeUpdateDelete()
        } finally {
            stmt.close()
        }
    }

    private fun queryBlocking(sql: String, binds: List<SqlValue>): List<SqlRow> {
        val db = helper.readableDatabase
        // rawQuery 的 selectionArgs 只接受字符串。SQLite 的列亲和性会把
        // TEXT 值转成 INTEGER/REAL 再比较，所以数字参数按字符串传是安全的
        // （表里没有 BLOB 列 —— 字节一律走文件，不进库）。
        val args = binds.map { it.asSelectionArg() }.toTypedArray()
        val cursor = db.rawQuery(sql, args)
        cursor.use { return readAll(it) }
    }

    private fun readAll(cursor: Cursor): List<SqlRow> {
        if (!cursor.moveToFirst()) return emptyList()
        val columns = buildMap {
            for (i in 0 until cursor.columnCount) put(cursor.getColumnName(i), i)
        }
        val out = ArrayList<SqlRow>(cursor.count)
        do {
            val values = ArrayList<SqlValue>(cursor.columnCount)
            for (i in 0 until cursor.columnCount) {
                values += when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> SqlValue.Null
                    Cursor.FIELD_TYPE_INTEGER -> SqlValue.Int64(cursor.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> SqlValue.Real(cursor.getDouble(i))
                    else -> SqlValue.Text(cursor.getString(i) ?: "")
                }
            }
            out += SqlRow(columns, values)
        } while (cursor.moveToNext())
        return out
    }
}
