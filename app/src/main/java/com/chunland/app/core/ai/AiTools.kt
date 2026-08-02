package com.chunland.app.core.ai

import kotlinx.serialization.Serializable

// 工具名 / 分级（对齐 iOS AITools.swift）：
// 工具的「定义（schema）+ 执行（handler）」按域拆在 tools/ 下各文件（见 AiToolRegistry）。
// 本文件只留跨域共享类型：工具名枚举、读/写分级、确认意图、作用域、OpenAI function schema wire。

enum class AiToolName(val wire: String) {
    SEARCH_PRODUCTS("search_products"),
    GET_PRODUCT_DETAIL("get_product_detail"),
    GET_CATEGORIES("get_categories"),
    ADD_TO_CART("add_to_cart"),
    GET_CART("get_cart"),
    PLACE_ORDER("place_order"),
    LIST_MY_ORDERS("list_my_orders"),
    GET_ORDER_DETAIL("get_order_detail"),

    // 代购域：仅 agent 身份下发（isAgentScoped），消费者会话永远看不到
    LIST_MY_CLAIMS("list_my_claims"),
    BUILD_PURCHASE_LIST("build_purchase_list"),
    SUMMARIZE_SETTLEMENTS("summarize_settlements"),
    PROPOSE_ADJUSTMENT("propose_adjustment"),

    // 商家域（AI 分类）：仅 merchant 身份下发（isMerchantScoped）
    LIST_STORE_PRODUCTS("list_store_products"),
    LIST_CATEGORY_SCHEMES("list_category_schemes"),
    CREATE_CATEGORY_SCHEME("create_category_scheme"),
    ASSIGN_CATEGORY_PRODUCTS("assign_category_products");

    /** 读/写分级 —— MUTATION 走 HITL（确认弹窗通过后才执行） */
    enum class Kind { READ_ONLY, MUTATION }

    val kind: Kind
        get() = when (this) {
            ADD_TO_CART, PLACE_ORDER, PROPOSE_ADJUSTMENT,
            CREATE_CATEGORY_SCHEME, ASSIGN_CATEGORY_PRODUCTS -> Kind.MUTATION
            else -> Kind.READ_ONLY
        }

    /**
     * 三身份可用集 —— AI 可用/可调用的工具是**当前活跃身份的函数**（单一真相源，对齐 iOS）。
     * 两侧共用同一判定：下发侧（AiToolRegistry.wireTools 裁剪发给模型的 schema）+
     * 执行侧（AiChatStore.executeTool 守卫）。执行侧必须再拦一道 —— 会话跨身份留存
     * （切身份不清历史），模型可能从历史里复调旧身份的工具名，「模型看不到」≠「调不到」。
     * 产品语义：购物/下单只属买家身份，代购/商家身份不做买家的事；
     * GET_ORDER_DETAIL 是唯一跨域例外（代购人跟进接单详情同样需要，服务端按参与方鉴权）。
     */
    val allowedIdentities: Set<String>
        get() = when (this) {
            SEARCH_PRODUCTS, GET_PRODUCT_DETAIL, GET_CATEGORIES,
            ADD_TO_CART, GET_CART, PLACE_ORDER, LIST_MY_ORDERS -> setOf("consumer")
            GET_ORDER_DETAIL -> setOf("consumer", "agent")
            LIST_MY_CLAIMS, BUILD_PURCHASE_LIST, SUMMARIZE_SETTLEMENTS, PROPOSE_ADJUSTMENT -> setOf("agent")
            LIST_STORE_PRODUCTS, LIST_CATEGORY_SCHEMES,
            CREATE_CATEGORY_SCHEME, ASSIGN_CATEGORY_PRODUCTS -> setOf("merchant")
        }

    fun allowedFor(identity: String): Boolean = identity in allowedIdentities

    /** 实时类工具 —— 结果随时间失效（购物车/订单/接单/店铺快照）。发送期把历史轮的
     *  这类结果折叠成过期占位（foldWireHistory），物理杜绝模型复用过期数据；
     *  搜索/详情/分类结果刻意不折叠（prompt 规则 3 明确允许复用），mutation 的简短
     *  确认文本（含订单号）保留作对话叙事。对齐 iOS AIToolName.resultIsVolatile。 */
    val resultVolatile: Boolean
        get() = when (this) {
            GET_CART, LIST_MY_ORDERS, GET_ORDER_DETAIL,
            LIST_MY_CLAIMS, BUILD_PURCHASE_LIST, SUMMARIZE_SETTLEMENTS,
            LIST_STORE_PRODUCTS, LIST_CATEGORY_SCHEMES -> true
            else -> false
        }

    /** 工具调用指示器的中文名（UI 用），单一真相源挂在枚举上 */
    val friendlyName: String
        get() = when (this) {
            SEARCH_PRODUCTS -> "搜索商品"
            GET_PRODUCT_DETAIL -> "查看详情"
            GET_CATEGORIES -> "浏览分类"
            ADD_TO_CART -> "加入购物车"
            GET_CART -> "查看购物车"
            PLACE_ORDER -> "提交订单"
            LIST_MY_ORDERS -> "查看我的订单"
            GET_ORDER_DETAIL -> "查看订单详情"
            LIST_MY_CLAIMS -> "查看接单进度"
            BUILD_PURCHASE_LIST -> "整理采购清单"
            SUMMARIZE_SETTLEMENTS -> "查看结算收益"
            PROPOSE_ADJUSTMENT -> "起草缺货改单"
            LIST_STORE_PRODUCTS -> "读取店铺商品"
            LIST_CATEGORY_SCHEMES -> "查看分类方案"
            CREATE_CATEGORY_SCHEME -> "创建分类方案"
            ASSIGN_CATEGORY_PRODUCTS -> "归类商品"
        }

    companion object {
        fun fromWire(raw: String): AiToolName? = entries.firstOrNull { it.wire == raw }

        /** 身份的中文标签（执行守卫拒绝文案 / UI 共用） */
        fun identityLabel(identity: String): String = when (identity) {
            "consumer" -> "买家"
            "agent" -> "代购人"
            "merchant" -> "商家"
            else -> identity
        }
    }
}

/**
 * 工具执行的结构化作用域（对齐 iOS AIToolScope）—— 这是给代码的硬约束，不是给模型的散文。
 * 进店等 scoped 入口据此把 search_products / get_categories 真正限定到当前商家；
 * 「默认作用于本店」这类承诺必须由它兑现，绝不许只写进 prompt 许愿（iOS 编造店铺目录 bug 的根因）。
 */
data class AiToolScope(
    val merchantId: Int? = null,
    val merchantName: String? = null,
) {
    companion object {
        val GLOBAL = AiToolScope()
    }
}

/** AI 想执行变更操作时先产出意图，由 UI 弹确认框（HITL） */
data class MutationIntent(
    val toolName: AiToolName,
    /** 给用户看的中文摘要 */
    val summary: String,
    /** 调试用参数快照 */
    val payload: Map<String, String> = emptyMap(),
)

// ---- OpenAI function schema wire（发给 AI 的工具描述；AiWireJson encodeDefaults=true 保证常量字段落 wire） ----

@Serializable
data class AiToolWire(
    val type: String = "function",
    val function: AiFunctionWire,
)

@Serializable
data class AiFunctionWire(
    val name: String,
    val description: String,
    val parameters: AiParametersWire,
)

@Serializable
data class AiParametersWire(
    val type: String = "object",
    val properties: Map<String, AiPropertyWire> = emptyMap(),
    val required: List<String> = emptyList(),
)

@Serializable
data class AiPropertyWire(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
)
