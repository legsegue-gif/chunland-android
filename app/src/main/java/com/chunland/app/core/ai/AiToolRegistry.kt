package com.chunland.app.core.ai

import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.tools.agentTools
import com.chunland.app.core.ai.tools.cartTools
import com.chunland.app.core.ai.tools.merchantTools
import com.chunland.app.core.ai.tools.orderTools
import com.chunland.app.core.ai.tools.productTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * mutation 工具「执行期解析」的产物（对齐 iOS PreparedMutation）。
 * 有 prepare 的工具在弹确认前先解析系统里的真实数据（地址簿、服务端报价等）：
 * 确认框展示的 intent 与随后执行的 execute 持有**同一份解析快照** ——
 * 「确认里看到的 = 实际执行的」由构造保证，而不是靠模型转述参数。
 * 与 AiToolScope 同一条红线的延伸：系统已有的数据（地址/价格）由代码解析，
 * 绝不让模型重新收集或复述。
 */
sealed interface PreparedMutation {
    /** 前置条件不满足（如地址簿为空、未达起送）：不弹确认，文本直接作为工具结果回给模型 */
    data class Abort(val text: String) : PreparedMutation

    /** 解析完成：intent 给用户确认，通过后执行 execute */
    class Ready(val intent: MutationIntent, val execute: suspend () -> String) : PreparedMutation
}

/**
 * 一个工具的「定义 + 执行」自包含描述（对齐 iOS AIToolSpec）。
 * 每个域在 tools/ 下自己的文件里声明若干 spec；orchestrator（AiChatStore）按 name 查 spec、
 * 跑 run 即可 —— 新增域只加一个 tools 文件 + registry 里追加一行。
 */
class AiToolSpec(
    val name: AiToolName,
    /** 发给 AI 的 function schema */
    val tool: AiToolWire,
    val kind: AiToolName.Kind,
    /** mutation 专用：据 args 生成给用户看的确认摘要；readOnly 为 null */
    val intentSummary: ((JsonObject) -> String)? = null,
    /** mutation 专用（可选）：执行期解析。设了它的工具，确认与执行整体走 prepare
     *  返回的快照，intentSummary / run 不再参与 —— 见 PreparedMutation */
    val prepare: (suspend (JsonObject, AiToolScope) -> PreparedMutation)? = null,
    /** 执行体：readOnly 立即跑；mutation 在确认通过后跑。返回给模型的文本结果。
     *  第二参数是会话的结构化作用域（AiToolScope）—— scoped 入口据此硬限定查询范围。 */
    val run: suspend (JsonObject, AiToolScope) -> String,
)

/**
 * 全部域工具的单一汇集点（对齐 iOS AIToolRegistry）。
 * handler 走 AppGraph 的 Retrofit（带 chunland token）；与 AI endpoint 的裸 client 永不交叉。
 */
class AiToolRegistry(graph: AppGraph) {

    private val specs: List<AiToolSpec> =
        productTools(graph) + cartTools(graph) + orderTools(graph) +
            agentTools(graph) + merchantTools(graph)

    fun spec(rawName: String): AiToolSpec? = specs.firstOrNull { it.name.wire == rawName }

    /**
     * 发给 AI 的工具 schema。**当前活跃身份是全局不变量**（对齐 iOS AIToolRegistry.tools）：
     * toolScope == null（tab 主对话）→ 该身份可用的全量（allowedIdentities，三域互斥）；
     * toolScope 非空（未来页面 scoped 会话）→ 集合 ∩ 身份可用集（守住「换身份后残留
     * context 越权」这条缝）。执行侧还有同判定的第二道守卫（AiChatStore.executeTool）。
     */
    fun wireTools(toolScope: Set<AiToolName>?, identity: String): List<AiToolWire> =
        specs.filter {
            it.name.allowedFor(identity) && (toolScope == null || it.name in toolScope)
        }.map { it.tool }
}

// ---- tools/ 各域共用的小工具 ----

/** schema 声明去样板 */
internal fun toolSchema(
    name: AiToolName,
    description: String,
    properties: Map<String, AiPropertyWire> = emptyMap(),
    required: List<String> = emptyList(),
): AiToolWire = AiToolWire(
    function = AiFunctionWire(
        name = name.wire,
        description = description,
        parameters = AiParametersWire(properties = properties, required = required),
    ),
)

internal fun prop(type: String, description: String, enum: List<String>? = null): AiPropertyWire =
    AiPropertyWire(type = type, description = description, enum = enum)

/** 参数取字符串：空白视为缺参 */
internal fun JsonObject.argString(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** 参数取整：模型可能传数字也可能传字符串数字，双兜底（对齐 iOS intArg） */
internal fun JsonObject.argInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

/** 工具输出的金额文案（wire 为 Double；只做显示，一切金额计算在服务端） */
internal fun aiMoney(v: Double?): String = when {
    v == null -> "?"
    v % 1.0 == 0.0 -> "%.0f".format(v)
    else -> v.toString()
}
