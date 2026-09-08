package com.chunland.app.core.ai

import com.chunland.app.core.ai.prompt.AiPrompts
import com.chunland.app.core.ai.tools.AiProvenanceKind
import com.chunland.app.core.ai.tools.AiToolName

/**
 * 工具执行的结构化作用域（对齐 iOS AIToolScope）。
 *
 * seedNote 是喂给模型的散文（模型可以理解错、也可以不理会）；这个是工具执行时的硬约束。
 * 进店等场景据此把查询真正限定到当前商家 ——「搜索默认作用于当前店」必须由代码兑现，
 * 不能靠提示词许愿（否则模型会把全局结果一本正经地当成本店数据，见进店 ✨ 编造目录 bug）。
 */
data class AiToolScope(
    /** 当前进店商家 id（null = 全局，不限定） */
    val merchantId: Int? = null,
    /** 商家名（仅用于工具输出文案，如「在 X 店内找到…」） */
    val merchantName: String? = null,
) {
    companion object {
        val GLOBAL = AiToolScope()
    }
}

/**
 * 页面与 AI 之间「唯一的耦合面」（纯值，对齐 iOS AIContext）。
 * 任何页面唤起 AI 时只产出一个 AiContext（经会话注册表换到一个会话实例），
 * 不持有任何 AI 逻辑/工具/模型接线 —— 这是「换掉内脏不波及页面」的关键。
 *
 * 字段职责显式分开：
 * - title      : sheet 头部展示（「✨ 店名」）
 * - seedNote   : 注入 system 的事实（用户不可见，只喂模型），省一轮工具调用
 * - welcome    : 该上下文的欢迎语（View 装饰，绝不进对话历史）
 * - tools      : 建议的工具子集（null = 当前身份全量；registry 再与身份可用集取交）
 * - scope      : 工具执行的结构化作用域（给代码，不是给模型）——「搜索默认作用于本店」
 *                必须由它兑现，绝不许只写进 seedNote 许愿（iOS 编造店铺目录 bug 根因）
 * - contextKey : 会话续聊的稳定 key（如 "store:1"）。同 key 复用同一会话实例，
 *                且 24h 内的历史会话会被续聊命中（跨进程，落库）
 */
data class AiContext(
    val title: String,
    val seedNote: String? = null,
    val welcome: String? = null,
    val tools: Set<AiToolName>? = null,
    val scope: AiToolScope = AiToolScope.GLOBAL,
    val contextKey: String? = null,
    /**
     * 页面上下文里天然合法的 id。用户就站在这个商品/订单页上，模型不必先查一遍
     * 才能对它下手 —— 不预置的话，商品详情页 ✨ 说「加购」会被 provenance 守卫自己挡住。
     */
    val seedProvenance: Map<AiProvenanceKind, List<String>> = emptyMap(),
) {
    companion object {
        /**
         * 店铺选择页 / 进店 ✨（对齐 iOS AIContext.store）：全量消费者工具（入口宽泛，不预设范围）。
         *
         * merchantId 非空（已进店）→ search_products / get_categories 经 [AiToolScope] 硬限定到该店；
         * 为 null（店铺选择页）→ 全局，不限定。
         */
        fun store(merchantId: Int? = null, merchantName: String? = null): AiContext {
            val label = merchantName?.let { "「$it」" } ?: "店铺"
            return AiContext(
                title = merchantName ?: "选购助手",
                seedNote = if (merchantId != null) {
                    "用户正在逛$label。可帮其搜索商品、查看分类、加购下单；" +
                        "search_products / get_categories 已自动限定在该店范围内，返回的就是本店数据。"
                } else {
                    "用户正在逛$label。可帮其搜索商品、查看分类、加购下单。"
                },
                welcome = "想在${label}买点什么？我可以帮你搜商品、加购、下单。",
                tools = null,
                scope = AiToolScope(merchantId = merchantId, merchantName = merchantName),
                contextKey = merchantId?.let { "store:$it" } ?: "store",
            )
        }

        /** 购物车页 ✨（对齐 iOS AIContext.cart）：看购物车 / 凑单 / 直接下单。 */
        fun cart(): AiContext = AiContext(
            title = "购物车",
            seedNote = "用户正在查看购物车。涉及购物车内容请每次调用 get_cart 获取最新数据，" +
                "不要复用历史结果。",
            welcome = "需要我帮你看看购物车、凑单或直接下单吗？",
            tools = setOf(
                AiToolName.GET_CART,
                AiToolName.SEARCH_PRODUCTS,
                AiToolName.PLACE_ORDER,
            ),
            contextKey = "cart",
        )

        /**
         * 商品详情页 ✨（对齐 iOS AIContext.product）：聚焦商品/购物车/下单，
         * 砍掉分类等噪音，但保留跨域（加购→下单）能力。
         */
        fun product(code: String, name: String): AiContext = AiContext(
            title = name,
            seedNote = "用户正在浏览商品「$name」（商品代码 $code）。涉及该商品的库存/价格/详情" +
                "请调用 get_product_detail 用代码 $code 获取最新数据。",
            welcome = "关于「$name」，有什么可以帮你？比如它值不值得买、加入购物车。",
            tools = setOf(
                AiToolName.GET_PRODUCT_DETAIL,
                AiToolName.ADD_TO_CART,
                AiToolName.GET_CART,
                AiToolName.PLACE_ORDER,
            ),
            contextKey = "product:$code",
            // 用户就在这个商品页上 —— 直接说「加购」不该被 provenance 挡
            seedProvenance = mapOf(AiProvenanceKind.PRODUCT to listOf(code)),
        )

        /** 订单详情页 ✨（对齐 iOS AIContext.order）：订单只读工具（查状态/进度）。 */
        fun order(id: Int, number: String?): AiContext {
            val label = number?.let { "订单 $it" } ?: "订单"
            return AiContext(
                title = label,
                seedNote = "用户正在查看$label（内部 id $id）。涉及该订单状态/进度请用 " +
                    "get_order_detail 传 order_id=$id 获取最新数据，不要复用历史结果。",
                welcome = "关于这笔订单，有什么可以帮你？比如它到哪了、怎么退、现在还能做什么。",
                tools = setOf(AiToolName.GET_ORDER_DETAIL, AiToolName.LIST_MY_ORDERS),
                contextKey = "order:$id",
                seedProvenance = mapOf(AiProvenanceKind.ORDER to listOf(id.toString())),
            )
        }

        /** 「我的订单」列表页 ✨（对齐 iOS AIContext.orders）。 */
        fun orders(): AiContext = AiContext(
            title = "我的订单",
            seedNote = "用户正在看「我的订单」列表。用 list_my_orders 查列表、get_order_detail " +
                "看某单详情；每次重新调用获取最新状态，不要复用历史结果。",
            welcome = "想了解哪笔订单？我可以帮你查进度、说明状态。",
            tools = setOf(AiToolName.LIST_MY_ORDERS, AiToolName.GET_ORDER_DETAIL),
            contextKey = "orders",
        )

        /**
         * 商家控制台 ✨（对齐 iOS AIContext.merchantConsole，AI 分类）：
         * 读商品/方案 + 建方案/归类（mutation 走 HITL 确认）。
         */
        fun merchantConsole(storeName: String? = null): AiContext {
            val label = storeName?.let { "「$it」" } ?: "你的店铺"
            return AiContext(
                title = storeName ?: "店铺助手",
                seedNote = "用户是商家，正在管理$label。帮其做商品分类：先 list_store_products " +
                    "拿商品、list_category_schemes 看现有方案；建新方案用 create_category_scheme" +
                    "（会弹确认）；归类用 assign_category_products（每个分类调用一次、给该分类" +
                    "全量商品 code，整体替换语义）。\n\n" + AiPrompts.classificationRules,
                welcome = "我可以帮你打理店铺分类——比如说「按吃穿住行用给我的店分类」，" +
                    "我会生成方案并把商品归好类（执行前会请你确认）。",
                tools = setOf(
                    AiToolName.LIST_STORE_PRODUCTS,
                    AiToolName.LIST_CATEGORY_SCHEMES,
                    AiToolName.CREATE_CATEGORY_SCHEME,
                    AiToolName.ASSIGN_CATEGORY_PRODUCTS,
                ),
                contextKey = "merchant-console",
            )
        }

        /** 代购工作台 ✨（对齐 iOS AIContext.workbench）：代购域全套工具 + 订单钻取。 */
        fun workbench(): AiContext = AiContext(
            title = "代购工作台",
            seedNote = "用户是代购人，正在看代购工作台。查接单进度用 list_my_claims，" +
                "进店前整理清单用 build_purchase_list，收入/结算用 summarize_settlements，" +
                "遇缺货可用 propose_adjustment 起草改单（需用户确认）；某单细节用 " +
                "get_order_detail。每次重新调用获取最新状态，不要复用历史结果。",
            welcome = "我可以帮你梳理接单进度、整理进店采购清单、算结算收益；遇到缺货还能帮你起草改单。",
            tools = setOf(
                AiToolName.LIST_MY_CLAIMS,
                AiToolName.BUILD_PURCHASE_LIST,
                AiToolName.SUMMARIZE_SETTLEMENTS,
                AiToolName.PROPOSE_ADJUSTMENT,
                AiToolName.GET_ORDER_DETAIL,
            ),
            contextKey = "agent-workbench",
        )
    }
}
