package com.chunland.app.core.ai.storage

/**
 * 检索分词（对齐 iOS AITextSegmenter.swift）。
 *
 * 为什么需要它 —— SQLite 自带的两个分词器对中文都不可用：
 *
 * - `unicode61`：把「帮我找100元内的坚果」切成**一个** token，搜「坚果」永远命中不了。
 *   它按 Unicode 类别切分，连续的中文全是 letter 类，于是整段成词。
 * - `trigram`：3 字符滑窗。3 字词、子串、英文、数字都正常，但**2 字词全军覆没**
 *   （坚果 / 订单 / 退款 / 天气 …）—— 而中文最常用的恰恰是 2 字词。
 *
 * 所以走第三条路：**写入与查询都在应用层按字分词**，匹配交给 `LIKE`。
 * 「坚果」→ 索引里是 ` 坚 果 `，查询用 `LIKE '% 坚 果 %'`，
 * 空格边界要求两个 token 相邻，因此「果坚」不会误命中。
 *
 * ⚠️ 本文件的分词规则必须与 iOS 的 `AITextSegmenter.swift` **逐字一致** ——
 * 一端改了规则而另一端没改，会导致索引与查询用不同的切法，搜索静默失效。
 */
object AiTextSegmenter {

    /**
     * 判定为「需要逐字切分」的字符。
     *
     * 覆盖中日韩：基本汉字、扩展 A、兼容汉字、日文假名、韩文音节。
     * 这些文字没有词间空格，必须逐字切；其余文字（拉丁、数字、西里尔等）
     * 本来就靠空格与标点分词，保持原样即可。
     */
    private fun isIdeograph(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x3040..0x30FF) ||   // 日文平假名 / 片假名
            (code in 0x3400..0x4DBF) ||      // CJK 扩展 A
            (code in 0x4E00..0x9FFF) ||      // CJK 基本
            (code in 0xF900..0xFAFF) ||      // CJK 兼容
            (code in 0xAC00..0xD7AF)         // 韩文音节
    }

    /**
     * 建立索引用：表意文字逐字用空格隔开，其余原样。
     *
     * ```
     * 「帮我找100元内的坚果」 → 「帮 我 找 100 元 内 的 坚 果」
     * 「order shipped」      → 「order shipped」
     * ```
     */
    fun segment(text: String): String {
        val tokens = mutableListOf<String>()
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) { tokens += buffer.toString(); buffer.clear() }
        }

        for (ch in text) {
            when {
                isIdeograph(ch) -> {
                    // 表意字单独成词 —— 中文没有词间空格，逐字切是唯一稳妥的切法
                    flush()
                    tokens += ch.toString()
                }
                ch.isLetter() || ch.isDigit() -> buffer.append(ch)
                else -> {
                    // 标点、符号、空白一律作分隔并丢弃。
                    // 不这么做，`[8510974]`、`search_products、get_cart` 这类
                    // 会连着符号成为一个 token，搜商品号或工具名就永远命中不了。
                    flush()
                }
            }
        }
        flush()
        return if (tokens.isEmpty()) "" else " " + tokens.joinToString(" ") + " "
    }

    /**
     * 用户输入 → 一组 `LIKE` 模式（多个词之间是 AND 关系，调用方逐个加条件）。
     *
     * 每个词各自分词后前后包 `%`。因为索引里的 token 以空格分隔且首尾补空，
     * `% 坚 果 %` 要求两个 token **相邻且对齐边界** —— 既不会命中「果…坚」，
     * 也不会把「坚」误当成「坚果」的一部分。
     *
     * 转义是**冗余防线**：分词已把非字母数字字符全当分隔丢掉，模式里不可能出现元字符。
     * 留着是为了将来改分词规则时不会悄悄打开 LIKE 注入面。
     */
    fun likePatterns(raw: String): List<String> =
        raw.split(Regex("\\s+"))
            .map { segment(it) }
            .filter { it.isNotEmpty() }
            .map { "%" + escapeLike(it) + "%" }

    /** LIKE 的转义字符，与 SQL 里的 `ESCAPE` 子句成对使用 */
    const val LIKE_ESCAPE = "\\"

    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * 在原文里定位查询词的位置，供 UI 做高亮。
     *
     * 不用 FTS 的 `snippet()`：它只能作用在索引列上，返回的是**分词后**的文本
     * （带一堆空格），拿去显示很难看，还原又会吃掉用户自己打的空格。
     * 命中的原文本来就要从 message_parts 取，直接在原文里找一遍更简单可靠。
     */
    fun highlightRanges(text: String, keyword: String): List<IntRange> {
        val terms = keyword.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()

        val ranges = mutableListOf<IntRange>()
        for (term in terms) {
            var start = 0
            while (start < text.length) {
                val i = text.indexOf(term, start, ignoreCase = true)
                if (i < 0) break
                ranges += i until (i + term.length)
                start = i + term.length
            }
        }
        return ranges.sortedBy { it.first }
    }

    /** 截取包含首个命中词的一段文本，供搜索结果列表展示 */
    fun excerpt(text: String, keyword: String, maxLength: Int = 60): String {
        val flat = text.replace('\n', ' ').trim()
        if (flat.length <= maxLength) return flat

        val first = highlightRanges(flat, keyword).firstOrNull()
            ?: return flat.take(maxLength) + "…"

        // 命中词居中：前面留三分之一，后面留三分之二
        val lead = maxLength / 3
        val start = maxOf(0, first.first - lead)
        val end = minOf(flat.length, start + maxLength)
        var out = flat.substring(start, end)
        if (start > 0) out = "…$out"
        if (end < flat.length) out += "…"
        return out
    }
}
