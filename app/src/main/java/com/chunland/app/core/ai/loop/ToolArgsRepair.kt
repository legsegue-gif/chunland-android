package com.chunland.app.core.ai.loop

import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.domain.AgentToolInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 工具参数修复（对齐 iOS ToolArgsRepair.swift）。
 *
 * 模型发来的参数残缺是**常态而非异常**：流被截断、字段名打错、把字符串发成数字。
 * 直接让 preflight 拒掉是最差的处理 —— 用户看到「执行失败」，模型收到一句
 * 「参数无效」多半原样再发一次，白烧两轮。
 *
 * 三个策略按代价从低到高排，能修就修，修不了再交给 preflight 拒绝。
 */
object ToolArgsRepair {

    data class Outcome(
        val input: AgentToolInput,
        /** 用了哪些策略（空 = 没动过）。记日志用 */
        val repairs: List<String>,
    ) {
        val didRepair: Boolean get() = repairs.isNotEmpty()
    }

    /** 尝试修复。只在**确实需要**时才动手 —— 参数本来就完好时零开销 */
    fun repair(
        name: String,
        input: AgentToolInput,
        rawInput: String,
        definition: AgentToolDefinition?,
    ): Outcome {
        if (definition == null || !needsRepair(input, definition)) {
            return Outcome(input, emptyList())
        }

        var working = input
        val repairs = mutableListOf<String>()

        // ① 截断修复：参数整体没解析出来，但原始流尾巴还在。
        //    模型偶尔在写完对象前被切断，补上闭合符号就能救回来。
        if (working.isEmpty) {
            val tail = rawInput.trim()
            if (tail.isNotEmpty()) {
                tryClose(tail)?.let { (fixed, suffix) ->
                    working = fixed
                    repairs += "截断补全(${suffix.ifEmpty { "原样" }})"
                }
            }
        }

        // ② 类型强转：必填字段有值但类型不对
        definition.required.forEach { field ->
            val value = working[field] ?: return@forEach
            when {
                value is JsonNull -> {
                    // null 视为缺失 —— 清掉，让策略③ 有机会用同名近似字段填上
                    working = working.without(field)
                    repairs += "清空null:$field"
                }
                value is JsonPrimitive && !value.isString -> {
                    working = working.with(field, JsonPrimitive(value.content))
                    repairs += "类型转换:$field"
                }
                // **刻意不转** 数组/字典：转成的调试字符串会被下游当成真实值
                // （比如把 ["a","b"] 当成一个字面路径），破坏性远大于直接拒绝
                else -> Unit
            }
        }

        // ③ 字段名纠错：必填字段缺失，但同级有个名字很像的
        definition.required.filter { working[it] == null }.forEach { field ->
            val candidate = nearestKey(field, working.keys, maxDistance = 1) ?: return@forEach
            // 不能抢走另一个必填字段的值
            if (candidate in definition.required) return@forEach
            working = working.renamed(candidate, field)
            repairs += "字段纠错:$candidate→$field"
        }

        return Outcome(working, repairs)
    }

    // MARK: - 判定

    fun needsRepair(input: AgentToolInput, definition: AgentToolDefinition): Boolean {
        if (input.isEmpty && definition.required.isNotEmpty()) return true
        definition.required.forEach { field ->
            val value = input[field] ?: return true
            if (input.isBlank(field)) return true
            when (value) {
                is JsonPrimitive -> if (!value.isString) return true
                is JsonArray, is JsonObject -> Unit
                else -> return true
            }
        }
        return false
    }

    // MARK: - 截断补全

    /**
     * 依次尝试补上常见的闭合组合。
     *
     * 顺序按出现频率排：最常见的是字符串没收尾（`"key":"val`），
     * 其次是对象没收尾，再次是嵌套结构。
     */
    private fun tryClose(tail: String): Pair<AgentToolInput, String>? {
        val suffixes = listOf("", "\"}", "\"", "}", "\"]}", "]}", "}}", "\"}}", "]", "]]")
        for (suffix in suffixes) {
            val parsed = runCatching {
                com.chunland.app.core.ai.provider.OpenAiWire.json
                    .parseToJsonElement(tail + suffix).jsonObject
            }.getOrNull() ?: continue
            if (parsed.isEmpty()) continue
            return AgentToolInput(parsed) to suffix
        }
        return null
    }

    // MARK: - 字段名近似

    /**
     * 在候选里找与 [target] 编辑距离 ≤ [maxDistance] 的键。
     *
     * 距离阈值刻意只给 1：`comand`→`command` 该修，
     * 但 `name`→`code` 这种距离 4 的绝不能乱认，那是在猜。
     */
    fun nearestKey(target: String, keys: Collection<String>, maxDistance: Int): String? =
        keys.filter { it != target }
            .map { it to levenshtein(it, target) }
            .filter { it.second <= maxDistance }
            .minByOrNull { it.second }
            ?.first

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        // 长度差已经超过 1 的直接判负，省掉整个矩阵
        if (kotlin.math.abs(a.length - b.length) > 1) return maxOf(a.length, b.length)

        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
}
