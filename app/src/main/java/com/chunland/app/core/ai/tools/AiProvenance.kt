package com.chunland.app.core.ai.tools

import com.chunland.app.core.ai.AiToolScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// MARK: - 会话级 provenance
//
// ⚠️ 规则与阈值必须与另一端客户端 **逐字一致**（有双端一致性校验兜底）。
//
// **它挡的是哪一档**：服务端已经挡住了「不存在 / 不属于本店 / 不可购买」——
// 那是所有权与合法性。挡不住的是**「有效、但我从没给你看过」**：模型把上一轮
// 看到的 A 商品代码记串成 B，或者干脆凭印象编一个恰好存在的 code。服务端照单全收，
// 因为那确实是一个合法商品。
//
// 所以这一层的判据不是「合不合法」而是「**这个 id 是不是本次对话里某个工具真的
// 返回过**」。它是模型可靠性的护栏，**不是安全边界** —— 真正的越权仍由服务端挡，
// 两者不可互相替代（改客户端就能绕过这里，但绕过后服务端照样拒）。

/** 受 provenance 约束的 id 类别。 */
enum class AiProvenanceKind(val wire: String) {
    PRODUCT("product"),
    ORDER("order"),
    ORDER_ITEM("orderItem"),
    CATEGORY("category");

    companion object {
        /** 服务端按 wire 名分组回传 id；认不出的类别**静默忽略**，不让老客户端整条解码失败。 */
        fun fromWire(raw: String): AiProvenanceKind? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * 一个会话「见过哪些 id」的记录者。
 *
 * 每个会话一个实例，随会话消亡。Mutex 串行化 ——
 * 只读工具是并发执行的（管道 5 路），登记会真的并发发生。
 */
class ProvenanceRecorder(seed: Map<AiProvenanceKind, List<String>> = emptyMap()) {

    companion object {
        /**
         * 每类最多记多少个。超出按先进先出淘汰。
         *
         * 30 轮循环里一个会话能见到的 id 有上限但不小（每次搜索 20 条），
         * 500 足够覆盖整场对话，又不至于让长会话无限涨。
         */
        const val CAP_PER_KIND = 500

        private fun merge(values: List<String>, list: MutableList<String>, set: MutableSet<String>) {
            for (value in values) {
                if (value.isEmpty() || value in set) continue
                list += value
                set += value
            }
            while (list.size > CAP_PER_KIND) {
                set -= list.removeAt(0)
            }
        }
    }

    private val mutex = Mutex()
    private val order = mutableMapOf<AiProvenanceKind, MutableList<String>>()
    private val index = mutableMapOf<AiProvenanceKind, MutableSet<String>>()

    init {
        for ((kind, values) in seed) {
            merge(values, order.getOrPut(kind) { mutableListOf() }, index.getOrPut(kind) { mutableSetOf() })
        }
    }

    /**
     * 登记本会话见过的 id。工具在拿到 DTO 时调用 —— **用 DTO 里的字段，
     * 不要从拼好的文本里反解**（文本格式一改，provenance 就会静默失效）。
     */
    suspend fun record(kind: AiProvenanceKind, values: List<String>) = mutex.withLock {
        merge(values, order.getOrPut(kind) { mutableListOf() }, index.getOrPut(kind) { mutableSetOf() })
    }

    suspend fun has(kind: AiProvenanceKind, value: String): Boolean = mutex.withLock {
        index[kind]?.contains(value) == true
    }

    /** 返回 values 里**没见过**的那些，保持原顺序（拒绝话术要能原样列出来）。 */
    suspend fun unseen(kind: AiProvenanceKind, values: List<String>): List<String> = mutex.withLock {
        val known = index[kind] ?: emptySet<String>()
        values.filter { it !in known }
    }
}

// MARK: - 工具执行上下文
//
// 工具执行需要的、来自会话而不是来自参数的那些东西。
// 从前这里只有 [AiToolScope]（进店限定），provenance 加进来后升成一个上下文 ——
// 把「会话给工具的东西」收在一个类型里，以后再加不必再改 16 个闭包签名。

data class AiToolContext(
    /** 结构化硬约束（进店时圈定 merchantId）。 */
    val scope: AiToolScope,
    /** 本会话见过的 id。只读工具登记，变更工具受它约束。 */
    val provenance: ProvenanceRecorder,
)
