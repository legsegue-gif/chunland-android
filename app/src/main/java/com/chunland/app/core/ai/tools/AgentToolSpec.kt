package com.chunland.app.core.ai.tools

import com.chunland.app.core.ai.AiToolScope
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
    /** 变更类专用：据参数生成给用户看的确认摘要。只读工具为 null */
    val intentSummary: ((AgentToolInput) -> String)? = null,
    /**
     * 变更类专用（可选）：执行期解析。
     *
     * 设了它的工具，确认与执行整体走 prepare 返回的快照 ——
     * [intentSummary] / [run] 不再参与。这是「确认里看到的 = 实际执行的」
     * 由构造保证，而不是靠模型转述参数。
     */
    val prepare: (suspend (AgentToolInput, AiToolScope) -> AgentPreparedMutation)? = null,
    /** 执行体。第二参数是当前会话的结构化作用域 —— 进店等场景据此硬限定查询范围 */
    val run: suspend (AgentToolInput, AiToolScope) -> String,
) {
    /** 每个工具都自动带上 tool_title —— 不必在每处手写一遍 */
    val definition: AgentToolDefinition = definition.withToolTitle()
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
