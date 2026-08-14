package com.chunland.app.core.ai.tools

import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.loop.AgentMutationIntent
import com.chunland.app.core.ai.loop.AgentPreparedMutation
import com.chunland.app.core.network.apiCall
import com.chunland.app.data.model.AddCartItemRequest
import com.chunland.app.data.model.Category
import com.chunland.app.data.model.DeliveryAddress
import com.chunland.app.data.model.PlaceOrderRequest
import com.chunland.app.data.model.QuoteRequest
import com.chunland.app.data.model.orderStatusLabel
import java.util.UUID

/**
 * 买家域工具：商品 / 购物车 / 订单（对齐 iOS AgentShoppingTools.swift）。
 *
 * 全部只属买家身份（[AiToolName.allowedIdentities] 门控，下发 + 执行两道），
 * 唯一例外是 get_order_detail（代购人跟进接单同样需要，服务端按参与方鉴权）。
 *
 * 搜索与分类感知作用域：进店上下文时硬限定到该店 ——
 * 「本店搜索」由代码兑现，不靠提示词许愿（否则模型会把全局数据当本店数据）。
 *
 * handler 走 [AppGraph] 的 Retrofit（带本项目 token），与 AI endpoint 的裸 client 永不交叉。
 */
internal fun agentShoppingTools(graph: AppGraph): List<AgentToolSpec> =
    productSpecs(graph) + cartSpecs(graph) + orderSpecs(graph)

// ---- 商品 ----

private fun productSpecs(graph: AppGraph): List<AgentToolSpec> = listOf(

    AgentToolSpec(
        name = AiToolName.SEARCH_PRODUCTS,
        definition = toolDef(
            AiToolName.SEARCH_PRODUCTS,
            "搜索商品。支持关键词、分类、价格区间、只看有货、排序 —— " +
                "一次问对比翻页筛选高效得多（如「100 元以内、有货、按价格从低到高」）。" +
                "店铺上下文中自动限定在当前店铺内。",
            params = listOf(
                "query" to strParam("搜索关键词，如「牛排」「咖啡」"),
                "category" to strParam("分类代码，来自 get_categories"),
                "price_min" to numParam("最低价（元）"),
                "price_max" to numParam("最高价（元）"),
                "in_stock" to boolParam("只看有货的，默认 false"),
                "sort" to strParam(
                    "排序方式",
                    values = listOf("relevance", "price_asc", "price_desc", "discount", "newest"),
                ),
                "limit" to intParam("返回数量，默认 10，最多 20"),
            ),
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { args, scope ->
            val limit = (args.int("limit") ?: 10).coerceIn(1, 20)
            val resp = apiCall {
                graph.productApi.list(
                    limit = limit,
                    category = args.string("category"),
                    keyword = args.string("query"),
                    merchant = scope.merchantId,
                    priceMin = args.double("price_min"),
                    priceMax = args.double("price_max"),
                    inStock = if (args.bool("in_stock") == true) true else null,
                    sort = args.string("sort"),
                    // 精简字段：一次 20 条，完整字段会吃掉大量上下文，
                    // 而挑商品只需要「叫什么、多少钱、有没有货」
                    fields = "compact",
                    withStats = true,
                )
            }
            val place = scope.merchantName?.let { "在「$it」店内" } ?: ""
            if (resp.items.isEmpty()) {
                "${place}没有找到匹配的商品。可以放宽条件再试（比如去掉价格限制或换个关键词）。"
            } else {
                val shown = resp.items.take(limit)
                val lines = shown.joinToString("\n") {
                    "[${it.code}] ${it.name} ¥${agentMoney(it.currentPrice)} ${if (it.outOfStock) "缺货" else "有货"}"
                }
                var out = "${place}找到 ${resp.pagination.total} 个商品（展示前 ${shown.size} 个）：\n$lines"
                // 价格分布让模型能判断「这个价位算便宜还是贵」，省一轮试探
                val stats = resp.stats
                if (stats != null && resp.pagination.total > limit) {
                    out += "\n（符合条件的商品价格 ¥${agentMoney(stats.minPrice ?: 0.0)}–" +
                        "¥${agentMoney(stats.maxPrice ?: 0.0)}，其中 ${stats.inStockCount} 件有货）"
                }
                out
            }
        },
    ),

    AgentToolSpec(
        name = AiToolName.GET_PRODUCT_DETAIL,
        definition = toolDef(
            AiToolName.GET_PRODUCT_DETAIL,
            "获取指定商品的详细信息（价格、库存、规格）",
            params = listOf("code" to strParam("商品代码，如 123456")),
            required = listOf("code"),
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { args, _ ->
            val p = apiCall { graph.productApi.detail(args.string("code") ?: "") }
            """
            商品：${p.name}
            价格：¥${agentMoney(p.currentPrice)}
            库存：${p.stockStatus ?: "未知"}
            单位：${p.unitType ?: "-"}
            随机重量：${if (p.randomWeight) "是（按实重计价）" else "否"}
            """.trimIndent()
        },
    ),

    AgentToolSpec(
        name = AiToolName.GET_CATEGORIES,
        definition = toolDef(
            AiToolName.GET_CATEGORIES,
            "获取商品分类及各分类的在售商品数，用于了解有哪些品类" +
                "（店铺上下文中返回当前店铺自己的分类）",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, scope ->
            val cats = apiCall { graph.categoryApi.tree(merchant = scope.merchantId, withCounts = true) }
            fun label(c: Category): String {
                val count = c.productCount?.let { "，$it 件" } ?: ""
                return "${c.name}（${c.code}$count）"
            }
            // 进店：返回该店自有分类；店铺没有分类导航时如实说，
            // 绝不回落全局树冒充本店分类
            if (scope.merchantId != null) {
                val place = scope.merchantName?.let { "「$it」" } ?: "该店铺"
                if (cats.isEmpty()) {
                    "${place}没有分类导航，可用 search_products 直接搜索店内商品。"
                } else {
                    "${place}的分类：" + cats.take(15).joinToString("、", transform = ::label)
                }
            } else {
                cats.filter { it.level == 1 }.take(10).joinToString("、", transform = ::label)
            }
        },
    ),
)

// ---- 购物车 ----

private fun cartSpecs(graph: AppGraph): List<AgentToolSpec> = listOf(

    AgentToolSpec(
        name = AiToolName.ADD_TO_CART,
        definition = toolDef(
            AiToolName.ADD_TO_CART,
            "将指定商品加入购物车",
            params = listOf(
                "product_code" to strParam("商品代码"),
                "quantity" to intParam("数量，默认 1"),
            ),
            required = listOf("product_code"),
        ),
        kind = AiToolName.Kind.MUTATION,
        intentSummary = { args ->
            "加入购物车：商品 ${args.string("product_code") ?: ""} × ${args.int("quantity") ?: 1}"
        },
        run = { args, _ ->
            val code = args.string("product_code") ?: ""
            val qty = args.int("quantity") ?: 1
            apiCall { graph.cartApi.addItem(AddCartItemRequest(productCode = code, quantity = qty)) }
            "已将商品 $code × $qty 加入购物车"
        },
    ),

    AgentToolSpec(
        name = AiToolName.GET_CART,
        definition = toolDef(
            AiToolName.GET_CART,
            "查看当前购物车内容和总价。**每次询问购物车都必须重新调用，" +
                "禁止复用历史结果**（用户可能在中间加/删了商品）。",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, _ ->
            val cart = apiCall { graph.cartApi.get() }
            if (cart.items.isEmpty()) {
                "购物车是空的"
            } else {
                val lines = cart.items.joinToString("\n") {
                    "${it.name} × ${it.quantity}  ¥${agentMoney(it.currentPrice)}"
                }
                // itemsTotal 是服务端算好的字符串，直接显示 —— 端上绝不本地加总
                "购物车（共 ${cart.items.size} 种商品）：\n$lines\n合计：¥${cart.itemsTotal}"
            }
        },
    ),
)

// ---- 订单 ----

private fun orderSpecs(graph: AppGraph): List<AgentToolSpec> = listOf(

    AgentToolSpec(
        name = AiToolName.PLACE_ORDER,
        definition = toolDef(
            AiToolName.PLACE_ORDER,
            "用购物车当前内容下单。收货地址自动使用用户地址簿的默认地址、" +
                "费用以服务端报价为准，两者都会在确认弹窗中展示给用户 —— " +
                "**不要向用户索要姓名/电话/地址，也不要自行报费用**。" +
                "用户没有地址时引导其到「我的 → 地址管理」添加；" +
                "想换地址时告知其到购物车结算页选择。",
            params = listOf("note" to strParam("配送备注（可选）")),
        ),
        kind = AiToolName.Kind.MUTATION,
        // 执行期解析：地址取地址簿、费用取服务端报价（与结算页同一 quote 端点，
        // 含距离代购费与起送校验），确认弹窗展示并执行的都是这份快照。
        // 模型全程接触不到地址明细 —— 既堵住「让用户口述地址 → areaCode 丢失 →
        // 距离费静默为 0」的计费旁路，地址簿 PII 也不进模型上下文。
        prepare = { args, _ ->
            val addresses = apiCall { graph.addressApi.list() }
            if (addresses.isEmpty()) {
                AgentPreparedMutation.Abort(
                    "用户的地址簿还没有收货地址，无法下单。请引导用户到" +
                        "「我的 → 地址管理」添加收货地址后再试；不要让用户在对话里口述地址。",
                )
            } else {
                val chosen = addresses.firstOrNull { it.isDefault } ?: addresses.first()
                val quote = apiCall { graph.orderApi.quote(QuoteRequest(areaCode = chosen.areaCode)) }
                val short = quote.groups.firstOrNull { !it.meetsMinOrder }
                if (short != null) {
                    AgentPreparedMutation.Abort(
                        "「${short.merchantName}」商品小计 ¥${agentMoney(short.itemsTotal)} " +
                            "未达起送金额 ¥${agentMoney(short.minOrderAmount)}，无法下单。" +
                            "可建议用户补足该店商品，或从购物车移除该店商品后再下单。",
                    )
                } else {
                    val delivery = DeliveryAddress(
                        name = chosen.name,
                        phone = chosen.phone,
                        address = chosen.address,
                        note = args.string("note"),
                        areaCode = chosen.areaCode,
                    )
                    val details = linkedMapOf(
                        "收货" to "${chosen.name} ${chosen.phone}",
                        "地址" to chosen.address,
                        "合计" to "¥${agentMoney(quote.grandTotal)}",
                    )
                    quote.groups.forEach { g ->
                        val key = if (quote.groups.size == 1) "费用" else g.merchantName
                        details[key] = "商品 ¥${agentMoney(g.itemsTotal)} + 平台费 ¥${agentMoney(g.platformFee)}" +
                            " + 代购费 ¥${agentMoney(g.agentFee)}"
                    }
                    val summary = if (quote.groups.size > 1) {
                        "用购物车内容下单（${quote.groups.size} 个商家，分别成单）"
                    } else {
                        "用购物车内容下单"
                    }
                    AgentPreparedMutation.Ready(
                        intent = AgentMutationIntent(
                            id = UUID.randomUUID().toString(),
                            toolName = AiToolName.PLACE_ORDER.wire,
                            summary = summary,
                            details = details,
                        ),
                    ) {
                        val batch = apiCall { graph.orderApi.place(PlaceOrderRequest(deliveryAddress = delivery)) }
                        val dest = "收货地址用的是用户确认过的地址簿地址（${chosen.name}，${chosen.address}）"
                        val only = batch.orders.firstOrNull()
                        if (batch.orderCount == 1 && only != null) {
                            "下单成功！订单号：${only.orderNumber}，" +
                                "总金额：¥${agentMoney(only.totalAmount)}，等待代购人接单。$dest。"
                        } else {
                            "下单成功！已按商家拆为 ${batch.orderCount} 笔订单，" +
                                "合计 ¥${batch.grandTotal}，等待代购人接单。$dest。"
                        }
                    }
                }
            }
        },
        // 不可达：place_order 恒经 prepare 的确认流程执行（管道优先走 prepare）
        run = { _, _ -> "内部错误：place_order 只能经确认流程执行。" },
    ),

    AgentToolSpec(
        name = AiToolName.LIST_MY_ORDERS,
        definition = toolDef(
            AiToolName.LIST_MY_ORDERS,
            "查看「我的订单」列表（订单号、状态、金额）。问及订单进度/历史时调用。" +
                "**每次都重新调用获取最新状态，禁止复用历史结果。**",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, _ ->
            val orders = apiCall { graph.orderApi.list() }
            if (orders.isEmpty()) {
                "你还没有订单。"
            } else {
                val lines = orders.take(20).joinToString("\n") { o ->
                    val count = o.itemCount?.let { "${it}件" } ?: ""
                    "#${o.orderNumber}（id:${o.id}）｜${orderStatusLabel(o.status)}" +
                        "｜¥${agentMoney(o.totalAmount)}｜$count"
                }
                "你的订单（共 ${orders.size} 笔）：\n$lines" +
                    "\n（需要某单详情时用 get_order_detail，order_id 传上面的 id）"
            }
        },
    ),

    AgentToolSpec(
        name = AiToolName.GET_ORDER_DETAIL,
        definition = toolDef(
            AiToolName.GET_ORDER_DETAIL,
            "查看某一订单的详情（状态、金额构成、商品、收货信息、当前可执行的操作）。" +
                "order_id 来自 list_my_orders 的 id 或当前页面上下文。**每次重新调用获取最新状态。**",
            params = listOf("order_id" to intParam("订单的数字 id")),
            required = listOf("order_id"),
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { args, _ ->
            val orderId = args.int("order_id")
            if (orderId == null) {
                "请提供 order_id（订单的数字 id）。可先用 list_my_orders 查到 id。"
            } else {
                val o = apiCall { graph.orderApi.detail(orderId) }
                var out = """
                订单 #${o.orderNumber}
                状态：${orderStatusLabel(o.status)}
                合计：¥${agentMoney(o.totalAmount)}（商品 ¥${agentMoney(o.itemsTotal)} + 平台费 ¥${agentMoney(o.platformFee)} + 代购费 ¥${agentMoney(o.agentFee)}）
                商品：${o.items.joinToString("、") { "${it.productSnapshot.name} × ${it.quantity}" }}
                收货：${o.deliveryAddress.name} ${o.deliveryAddress.address}
                下单时间：${o.createdAt}
                """.trimIndent()
                val actions = o.availableActions
                if (!actions.isNullOrEmpty()) {
                    out += "\n你现在可以：${actions.joinToString("、") { it.label }}"
                }
                out
            }
        },
    ),
)
