package com.chunland.app.core.ai.storage

import android.database.sqlite.SQLiteStatement

/**
 * SQL 绑定值（对齐 iOS SQLValue）。
 *
 * 没有 Blob 分支 —— 字节一律走文件系统，永远不进数据库（见 MediaStore）。
 */
sealed interface SqlValue {

    data object Null : SqlValue
    data class Int64(val value: Long) : SqlValue
    data class Real(val value: Double) : SqlValue
    data class Text(val value: String) : SqlValue

    fun bindTo(stmt: SQLiteStatement, index: Int) {
        when (this) {
            is Null -> stmt.bindNull(index)
            is Int64 -> stmt.bindLong(index, value)
            is Real -> stmt.bindDouble(index, value)
            is Text -> stmt.bindString(index, value)
        }
    }

    /** rawQuery 的 selectionArgs 只接受字符串；NULL 用 null 元素表达 */
    fun asSelectionArg(): String? = when (this) {
        is Null -> null
        is Int64 -> value.toString()
        is Real -> value.toString()
        is Text -> value
    }

    val stringValue: String? get() = (this as? Text)?.value

    val intValue: Int?
        get() = when (this) {
            is Int64 -> value.toInt()
            is Real -> value.toInt()
            is Text -> value.toIntOrNull()
            is Null -> null
        }

    val longValue: Long?
        get() = when (this) {
            is Int64 -> value
            is Real -> value.toLong()
            is Text -> value.toLongOrNull()
            is Null -> null
        }

    val doubleValue: Double?
        get() = when (this) {
            is Real -> value
            is Int64 -> value.toDouble()
            is Text -> value.toDoubleOrNull()
            is Null -> null
        }

    val boolValue: Boolean? get() = intValue?.let { it != 0 }

    val isNull: Boolean get() = this is Null

    companion object {
        fun of(v: String?): SqlValue = v?.let { Text(it) } ?: Null
        fun of(v: Int?): SqlValue = v?.let { Int64(it.toLong()) } ?: Null
        fun of(v: Long?): SqlValue = v?.let { Int64(it) } ?: Null
        fun of(v: Boolean?): SqlValue = v?.let { Int64(if (it) 1L else 0L) } ?: Null
        fun of(v: Double?): SqlValue = v?.let { Real(it) } ?: Null

        fun text(v: String): SqlValue = Text(v)
        fun int(v: Int): SqlValue = Int64(v.toLong())
        fun long(v: Long): SqlValue = Int64(v)
        fun bool(v: Boolean): SqlValue = Int64(if (v) 1L else 0L)
    }
}

/**
 * 查询结果的一行。
 *
 * `columns` 在同一次查询的所有行之间共享同一个 Map 实例，
 * 所以每行多带一个映射表不产生额外开销。
 */
class SqlRow(
    private val columns: Map<String, Int>,
    private val values: List<SqlValue>,
) {
    operator fun get(name: String): SqlValue? =
        columns[name]?.let { values.getOrNull(it) }

    fun string(name: String): String? = this[name]?.stringValue
    fun int(name: String): Int? = this[name]?.intValue
    fun long(name: String): Long? = this[name]?.longValue
    fun double(name: String): Double? = this[name]?.doubleValue
    fun bool(name: String): Boolean? = this[name]?.boolValue

    /** 毫秒时间戳列 */
    fun epochMillis(name: String): Long? = this[name]?.longValue
}
