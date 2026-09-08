package com.chunland.app.core.ai.tools

import com.chunland.app.core.ai.domain.AgentParamType
import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.domain.AgentToolInput
import com.chunland.app.core.ai.domain.AgentToolParam
import com.chunland.app.core.ai.loop.AgentPreparedMutation

/**
 * 一个工具的「定义 + 执行」自包含描述（对齐 iOS AgentToolSpec.swift）。
 *
 * 定义用 [AgentToolDefinition]（provider 无关，自带 JSON Schema 投影与 tool_title），
 * 参数用 [AgentToolInput]（强类型，不是裸 JsonObject 到处判类型）。
 * 执行逻辑本身沿用既有实现 —— 那些文案与兜底都是踩坑后调过的。
 *
 * 每个域在自己的文件里声明若干 spec，[AgentToolRegistry] 汇集。
 * 新增域 = 加一个文件 + 在注册表里加一行，循环层零改动。
 */
class AgentToolSpec(
    val name: AiToolName,
    definition: AgentToolDefinition,
    val kind: AiToolName.Kind,
    /**
     * 工具体是否已搬到服务端（R5）。
     *
     * true = [run] 不再被调用，改打 `POST /ai/tools/call`；服务端返回的文本
     * **已消毒且已围栏**，端上原样透传。迁移期逐个翻，翻完这个标记就可以删掉。
     */
    val remote: Boolean = false,
    /**
     * 变更工具的执行期解析是否也在服务端（R5）。
     *
     * true = 走 `POST /ai/tools/prepare` 拿票据与意图，用户确认后
     * `POST /ai/tools/commit` 执行那份服务端快照。
     * **「确认里看到的 = 实际执行的」由服务端持有快照来保证。**
     */
    val remotePrepare: Boolean = false,
    /** 变更类专用：据参数生成给用户看的确认摘要。只读工具为 null */
    val intentSummary: ((AgentToolInput) -> String)? = null,
    /**
     * 变更类专用（可选）：执行期解析。
     *
     * 设了它的工具，确认与执行整体走 prepare 返回的快照 ——
     * [intentSummary] / [run] 不再参与。这是「确认里看到的 = 实际执行的」
     * 由构造保证，而不是靠模型转述参数。
     */
    val prepare: (suspend (AgentToolInput, AiToolContext) -> AgentPreparedMutation)? = null,
    /**
     * 执行体。第二参数是当前会话给工具的东西：结构化作用域（进店硬限定查询范围）
     * 与 provenance 记录者（只读工具登记见过的 id，变更工具受其约束）。
     */
    val run: suspend (AgentToolInput, AiToolContext) -> String,
) {
    /** 每个工具都自动带上 tool_title —— 不必在每处手写一遍 */
    val definition: AgentToolDefinition = definition.withToolTitle()
}

// MARK: - 列表类工具的行数上限
//
// 与 `AiFence.MAX_RESULT_CHARS` 是两道，不重复：字符上限兜总量（防一条结果吃掉整个
// 窗口），行数上限保证**截断落在语义边界上** —— 字符截断会把最后一行切成半截，
// 模型可能把半截商品名当成完整的。
//
// 取 50 而不是同文件其他工具的 20：店铺全量商品与合并采购清单本就是长列表，
// 20 行会让模型看不全而反复追问。

object AgentToolLimits {
    /** 长列表类工具单次最多列多少行。 */
    const val MAX_ROWS = 50
    /** 被截断时附在末尾的说明（要告诉模型「还有」，否则它会当成全部）。 */
    const val ROWS_TRUNCATED = "（列表过长，只列出前 %d 条，共 %d 条）"
}

// ---- 构造便利：把「参数表 + 必填列表」写紧凑，减少每个工具定义的样板 ----

internal fun toolDef(
    name: AiToolName,
    description: String,
    params: List<Pair<String, AgentToolParam>> = emptyList(),
    required: List<String> = emptyList(),
): AgentToolDefinition = AgentToolDefinition(
    name = name.wire,
    description = description,
    parameters = params.toMap(),
    required = required,
    propertyOrdering = params.map { it.first },
)

internal fun strParam(description: String, values: List<String>? = null): AgentToolParam =
    AgentToolParam(AgentParamType.STRING, description, enumValues = values)

internal fun intParam(description: String): AgentToolParam =
    AgentToolParam(AgentParamType.INTEGER, description)

internal fun numParam(description: String): AgentToolParam =
    AgentToolParam(AgentParamType.NUMBER, description)

internal fun boolParam(description: String): AgentToolParam =
    AgentToolParam(AgentParamType.BOOLEAN, description)

/**
 * 工具输出里的金额文案。
 *
 * wire 是 Double，直接 toString 会得到「88.0」。**只做显示** ——
 * 一切金额计算在服务端，端上永不复算。
 */
internal fun agentMoney(v: Double?): String = when {
    v == null -> "?"
    v % 1.0 == 0.0 -> "%.0f".format(v)
    else -> v.toString()
}
