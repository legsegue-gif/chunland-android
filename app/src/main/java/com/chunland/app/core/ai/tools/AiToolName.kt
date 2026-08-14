package com.chunland.app.core.ai.tools

/**
 * 工具名与身份可用集。
 *
 * **这是三身份工具门控的单一真相源。**
 *
 * 两侧共用同一判定：
 * - 下发侧 [AgentToolRegistry.availableTools] 裁剪发给模型的 schema
 * - 执行侧 [AgentToolRegistry.isAvailable] 在管道 preflight 阶段再拦一次
 *
 * 执行侧必须存在 —— 会话跨身份留存（切身份不清历史），模型可能从历史里
 * 复调旧身份的工具名，「模型看不到」不等于「调不到」。
 */
enum class AiToolName(val wire: String) {
    SEARCH_PRODUCTS("search_products"),
    GET_PRODUCT_DETAIL("get_product_detail"),
    GET_CATEGORIES("get_categories"),
    ADD_TO_CART("add_to_cart"),
    GET_CART("get_cart"),
    PLACE_ORDER("place_order"),
    LIST_MY_ORDERS("list_my_orders"),
    GET_ORDER_DETAIL("get_order_detail"),

    // 代购域：仅代购身份可用，买家/商家会话永远看不到也调不到
    LIST_MY_CLAIMS("list_my_claims"),
    BUILD_PURCHASE_LIST("build_purchase_list"),
    SUMMARIZE_SETTLEMENTS("summarize_settlements"),
    PROPOSE_ADJUSTMENT("propose_adjustment"),

    // 商家域（AI 分类）：仅商家身份可用
    LIST_STORE_PRODUCTS("list_store_products"),
    LIST_CATEGORY_SCHEMES("list_category_schemes"),
    CREATE_CATEGORY_SCHEME("create_category_scheme"),
    ASSIGN_CATEGORY_PRODUCTS("assign_category_products");

    /** 读 / 写分级 —— MUTATION 走 HITL（确认通过后才执行） */
    enum class Kind { READ_ONLY, MUTATION }

    val kind: Kind
        get() = when (this) {
            ADD_TO_CART, PLACE_ORDER, PROPOSE_ADJUSTMENT,
            CREATE_CATEGORY_SCHEME, ASSIGN_CATEGORY_PRODUCTS -> Kind.MUTATION
            else -> Kind.READ_ONLY
        }

    /**
     * 三身份可用集 —— 工具可用集是**当前活跃身份的函数**。
     *
     * 产品语义：购物/下单只属买家，接单/结算只属代购，店铺管理只属商家；
     * [GET_ORDER_DETAIL] 是唯一跨域例外（代购人跟进接单详情同样需要，
     * 服务端按订单参与方鉴权）。
     */
    val allowedIdentities: Set<String>
        get() = when (this) {
            SEARCH_PRODUCTS, GET_PRODUCT_DETAIL, GET_CATEGORIES,
            ADD_TO_CART, GET_CART, PLACE_ORDER, LIST_MY_ORDERS -> setOf("consumer")
            GET_ORDER_DETAIL -> setOf("consumer", "agent")
            LIST_MY_CLAIMS, BUILD_PURCHASE_LIST, SUMMARIZE_SETTLEMENTS,
            PROPOSE_ADJUSTMENT -> setOf("agent")
            LIST_STORE_PRODUCTS, LIST_CATEGORY_SCHEMES,
            CREATE_CATEGORY_SCHEME, ASSIGN_CATEGORY_PRODUCTS -> setOf("merchant")
        }

    fun allowedFor(identity: String): Boolean = identity in allowedIdentities

    /**
     * 实时类工具 —— 结果随时间失效（购物车 / 订单 / 接单 / 店铺快照）。
     *
     * 上下文治理据此把旧轮的这类结果换成过期占位，物理杜绝模型复用过期数据；
     * 搜索/详情/分类结果刻意不折叠（prompt 明确允许复用），
     * 变更类的简短确认文本（含订单号）保留作对话叙事。
     */
    val resultVolatile: Boolean
        get() = when (this) {
            GET_CART, LIST_MY_ORDERS, GET_ORDER_DETAIL,
            LIST_MY_CLAIMS, BUILD_PURCHASE_LIST, SUMMARIZE_SETTLEMENTS,
            LIST_STORE_PRODUCTS, LIST_CATEGORY_SCHEMES -> true
            else -> false
        }

    /**
     * 工具指示器的中文名（UI 用）。
     *
     * 挂在枚举上 = 单一真相源，任意处都能直接读，避免重复 switch。
     * 模型给了 tool_title 时优先显示那个（更具体）。
     */
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
