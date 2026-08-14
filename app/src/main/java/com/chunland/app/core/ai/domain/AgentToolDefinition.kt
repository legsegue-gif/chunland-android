package com.chunland.app.core.ai.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 工具定义（provider 无关，对齐 iOS AgentToolDefinition.swift）。
 *
 * 发给模型的 function schema 的中间表示。各 provider 自己把它翻译成自家格式。
 *
 * 与业务侧工具注册表的分工：注册表管「有哪些工具、谁能用、怎么执行」，
 * 这里只管「怎么描述给模型」。
 */

enum class AgentParamType(val wire: String) {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    ARRAY("array"),
    OBJECT("object"),
}

data class AgentToolParam(
    val type: AgentParamType,
    val description: String,
    /** 枚举值。写清楚可选集合能显著降低模型瞎填的概率 */
    val enumValues: List<String>? = null,
    /** [AgentParamType.ARRAY] 时的元素类型 */
    val itemType: AgentParamType? = null,
)

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParam>,
    val required: List<String>,
    /** 参数在 schema 里的生成顺序。部分模型对顺序敏感（先给关键参数命中率更高） */
    val propertyOrdering: List<String>? = null,
) {

    /** 生成 `parameters` 的 JSON Schema 对象 */
    fun parametersSchema(): JsonObject {
        val order = propertyOrdering ?: parameters.keys.sorted()
        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                order.forEach { key ->
                    val p = parameters[key] ?: return@forEach
                    putJsonObject(key) {
                        put("type", p.type.wire)
                        put("description", p.description)
                        p.enumValues?.takeIf { it.isNotEmpty() }?.let { values ->
                            put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                        }
                        if (p.type == AgentParamType.ARRAY && p.itemType != null) {
                            putJsonObject("items") { put("type", p.itemType.wire) }
                        }
                    }
                }
            }
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
    }

    /** OpenAI 兼容格式的完整工具描述 */
    fun openAISchema(): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            put("parameters", parametersSchema())
        }
    }

    /**
     * 补齐 [TOOL_TITLE_KEY]（业务侧注册表统一走这里，避免每个工具手写一遍）。
     */
    fun withToolTitle(): AgentToolDefinition {
        if (parameters.containsKey(TOOL_TITLE_KEY)) return this
        val order = (propertyOrdering ?: parameters.keys.sorted())
            .filterNot { it == TOOL_TITLE_KEY }
        return copy(
            parameters = parameters + (TOOL_TITLE_KEY to TOOL_TITLE_PARAM),
            required = required + TOOL_TITLE_KEY,
            propertyOrdering = listOf(TOOL_TITLE_KEY) + order,
        )
    }

    companion object {
        /**
         * 每个工具都带的自述参数：模型自己写一句给用户看的说明。
         *
         * 收益远大于成本 —— 工具指示器从「搜索商品」变成「在本店找 100 元内的坚果」，
         * 用户能看懂 AI 正在做什么，而不是看一个笼统的工具名。
         */
        const val TOOL_TITLE_KEY = "tool_title"

        val TOOL_TITLE_PARAM = AgentToolParam(
            type = AgentParamType.STRING,
            description = "用一句话（5-15 字）说明这次调用在做什么，展示给用户看，" +
                "例如「在本店找 100 元内的坚果」「查看订单配送进度」。用与用户相同的语言。",
        )
    }
}

/**
 * 执行前校验（对齐 iOS AgentToolPreflight）。
 *
 * 模型发来空参数或字段名打错是常态：流截断、笔误、把必填当选填。
 * 直接让 handler 崩或返回「执行出错」是最差的处理 —— 用户看到失败，
 * 模型也不知道自己错在哪，多半会原样重发一次。
 */
object AgentToolPreflight {

    data class Rejection(
        /** 给日志与 UI 看的简短原因 */
        val reason: String,
        /**
         * 给模型看的完整说明。
         *
         * **必须明确禁止原样重试** —— 不写这句的话，模型收到「参数无效」
         * 会理解成「再发一次试试」，于是同样的空参数再来一遍。
         */
        val modelMessage: String,
    )

    /** 校验必填字段。返回 null 表示通过 */
    fun validate(
        name: String,
        input: AgentToolInput,
        definition: AgentToolDefinition?,
    ): Rejection? {
        if (definition == null) {
            return Rejection(
                reason = "未知工具 $name",
                modelMessage = "工具 $name 不存在。请从当前可用的工具列表中选择，不要重复调用这个名字。",
            )
        }

        val missing = definition.required.filter { input.isBlank(it) }
        if (missing.isEmpty()) return null

        val list = missing.joinToString("、")
        return Rejection(
            reason = "缺少必填参数：$list",
            modelMessage = "调用 $name 被拒绝：缺少必填参数 $list（为空或未提供）。" +
                "请补全这些参数后重新调用，**不要用同样的空参数重试**。",
        )
    }
}
