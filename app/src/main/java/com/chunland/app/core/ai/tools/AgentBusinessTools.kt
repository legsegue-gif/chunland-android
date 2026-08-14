package com.chunland.app.core.ai.tools

import com.chunland.app.core.AppGraph
import com.chunland.app.core.network.apiCall
import com.chunland.app.data.model.AddCategoryRequest
import com.chunland.app.data.model.AdjustmentDetail
import com.chunland.app.data.model.AdjustmentItem
import com.chunland.app.data.model.CreateSchemeRequest
import com.chunland.app.data.model.ProposeAdjustmentRequest
import com.chunland.app.data.model.SetCategoryProductsRequest
import com.chunland.app.data.model.settlementStatusLabel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 代购域 + 商家域工具（对齐 iOS AgentBusinessTools.swift）。
 *
 * 两域都只对各自身份可用（[AiToolName.allowedIdentities] 门控，下发 + 执行两道），
 * 服务端另有 requireRole 双保险。
 */
internal fun agentBusinessTools(graph: AppGraph): List<AgentToolSpec> =
    agentSpecs(graph) + merchantSpecs(graph)

// ---- 代购域 ----

private fun agentSpecs(graph: AppGraph): List<AgentToolSpec> = listOf(

    AgentToolSpec(
        name = AiToolName.LIST_MY_CLAIMS,
        definition = toolDef(
            AiToolName.LIST_MY_CLAIMS,
            "查看我（代购人）的接单进度：待办分组计数 + 接单列表（订单号、状态、金额）。" +
                "可选 status 只看某一状态。**每次重新调用获取最新状态，禁止复用历史结果。**",
            params = listOf(
                "status" to strParam(
                    "可选。只看某状态的接单",
                    values = listOf("CLAIMED", "PAID", "PURCHASING", "DELIVERING", "DELIVERED"),
                ),
            ),
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { args, _ ->
            val status = args.string("status")
            val (d, orders) = coroutineScope {
                val dash = async { apiCall { graph.agentProfileApi.dashboard() } }
                val list = async { apiCall { graph.orderApi.list(status = status, scope = "mine") } }
                dash.await() to list.await()
            }
            val c = d.counts
            var out = "待办概览：待买家支付 ${c.claimed}｜待采购 ${c.paid}" +
                "｜采购中 ${c.purchasing}（缺小票 ${c.purchasingNoReceipt}、" +
                "改单待答复 ${c.purchasingPendingAdjustment}）" +
                "｜配送中 ${c.delivering}｜待买家确认 ${c.delivered}"

            out += if (orders.isEmpty()) {
                if (status != null) "\n该状态下暂无订单。" else "\n还没有接过单。"
            } else {
                val lines = orders.take(20).joinToString("\n") { o ->
                    "#${o.orderNumber}（id:${o.id}）｜${claimStatusLabel(o.status)}｜¥${agentMoney(o.totalAmount)}"
                }
                "\n接单列表（共 ${orders.size} 笔）：\n$lines" +
                    "\n（某单详情用 get_order_detail，order_id 传上面的 id）"
            }
            out
        },
    ),

    AgentToolSpec(
        name = AiToolName.BUILD_PURCHASE_LIST,
        definition = toolDef(
            AiToolName.BUILD_PURCHASE_LIST,
            "整理合并采购清单：把我待采购/采购中的订单按商家分组、同商品跨单聚合数量，" +
                "并标注各单小票凭证状态。进店采购前调用。**每次重新调用获取最新数据。**",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, _ ->
            val list = apiCall { graph.agentProfileApi.purchaseList() }
            if (list.groups.isEmpty()) {
                "当前没有待采购的订单（待采购/采购中状态才会进清单）。"
            } else {
                list.groups.joinToString("\n\n") { g ->
                    val items = g.items.joinToString("\n") { item ->
                        val size = item.selectedSize?.let { "（尺码 $it）" } ?: ""
                        val from = item.breakdown.joinToString("、") {
                            "…${it.orderNumber.takeLast(6)} ×${it.quantity}"
                        }
                        "· ${item.name}$size ×${item.totalQuantity}（来自 $from）"
                    }
                    val receipts = g.orders.joinToString("、") { o ->
                        "…${o.orderNumber.takeLast(6)}（id:${o.id}）${if (o.hasReceipt) "已传小票" else "待传小票"}"
                    }
                    "【${g.merchantName}】${g.orders.size} 单 ${g.items.size} 种商品\n$items\n小票状态：$receipts"
                }
            }
        },
    ),

    AgentToolSpec(
        name = AiToolName.SUMMARIZE_SETTLEMENTS,
        definition = toolDef(
            AiToolName.SUMMARIZE_SETTLEMENTS,
            "查看我（代购人）的结算收益：待结算/已结算总额 + 最近结算明细" +
                "（每单的货款返还、代购费、平台费）。问及收入/结算/某单赚多少时调用。" +
                "**每次重新调用获取最新数据。**",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, _ ->
            val s = apiCall { graph.agentProfileApi.settlements() }
            var out = "待结算 ¥${agentMoney(s.pendingTotal)}｜已结算 ¥${agentMoney(s.paidTotal)}"
            out += if (s.items.isEmpty()) {
                "\n还没有结算记录（订单完成后自动记账）。"
            } else {
                val lines = s.items.take(15).joinToString("\n") { r ->
                    "#${r.orderNumber}｜应结 ¥${agentMoney(r.netPayable)}" +
                        "（货款 ¥${agentMoney(r.itemsReimburse)} + 代购费 ¥${agentMoney(r.agentFee)}）" +
                        "｜${settlementStatusLabel(r.status)}"
                }
                "\n最近结算（共 ${s.items.size} 笔）：\n$lines"
            }
            out
        },
    ),

    AgentToolSpec(
        name = AiToolName.PROPOSE_ADJUSTMENT,
        definition = toolDef(
            AiToolName.PROPOSE_ADJUSTMENT,
            "缺货改单：对采购中的某个订单商品发起「缺货移除」或「减量」，提交后由买家确认。" +
                "order_id/order_item_id 先用 get_order_detail 查到。MVP 只支持下调，不能加价加量。",
            params = listOf(
                "order_id" to intParam("订单的数字 id"),
                "order_item_id" to intParam("订单内商品条目的数字 id（get_order_detail 可查）"),
                "action" to strParam(
                    "remove=缺货整项移除；reduce_qty=按缺货数量下调",
                    values = listOf("remove", "reduce_qty"),
                ),
                "new_quantity" to intParam("action=reduce_qty 时必填：下调后的数量（须小于原数量）"),
                "note" to strParam("给买家看的说明（可选），如「到店只剩 1 件」"),
            ),
            required = listOf("order_id", "order_item_id", "action"),
        ),
        kind = AiToolName.Kind.MUTATION,
        intentSummary = { args ->
            val itemId = args.int("order_item_id") ?: 0
            val what = if (args.string("action") == "remove") {
                "缺货移除商品条目 #$itemId"
            } else {
                "商品条目 #$itemId 数量下调为 ${args.int("new_quantity") ?: 0}"
            }
            "对订单 #${args.int("order_id") ?: 0} 发起缺货改单：$what（提交后需买家确认）"
        },
        run = { args, _ ->
            val orderId = args.int("order_id")
            val itemId = args.int("order_item_id")
            val action = args.string("action")
            val newQuantity = args.int("new_quantity")
            when {
                orderId == null || itemId == null || action !in listOf("remove", "reduce_qty") ->
                    "参数不全：需要 order_id、order_item_id 和 action（remove/reduce_qty）。" +
                        "可先用 get_order_detail 查条目 id。"

                action == "reduce_qty" && newQuantity == null ->
                    "action=reduce_qty 时必须提供 new_quantity（下调后的数量）。"

                else -> {
                    val adj = apiCall {
                        graph.orderApi.proposeAdjustment(
                            orderId,
                            ProposeAdjustmentRequest(
                                kind = "out_of_stock",
                                detail = AdjustmentDetail(
                                    items = listOf(
                                        AdjustmentItem(
                                            orderItemId = itemId,
                                            action = action!!,
                                            newQuantity = newQuantity,
                                        ),
                                    ),
                                ),
                                note = args.string("note"),
                            ),
                        )
                    }
                    "改单已提交（金额变化 ¥${agentMoney(adj.amountDelta)}），等待买家确认。" +
                        "买家接受后差额自动部分退款。"
                }
            }
        },
    ),
)

// ---- 商家域 ----
//
// AI 只在编辑期参与：生成建议 → 确认弹窗 → 普通 REST 落库；
// 消费者浏览读的是 DB，与 AI 无关。

private fun merchantSpecs(graph: AppGraph): List<AgentToolSpec> = listOf(

    AgentToolSpec(
        name = AiToolName.LIST_STORE_PRODUCTS,
        definition = toolDef(
            AiToolName.LIST_STORE_PRODUCTS,
            "读取我店铺的全部商品（code、名称、价格、上架状态）。" +
                "做分类归类前必须先调用它拿到商品清单。**每次重新调用获取最新数据。**",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, _ ->
            val products = apiCall { graph.merchantConsoleApi.products() }.items
            if (products.isEmpty()) {
                "店里还没有商品。"
            } else {
                val lines = products.joinToString("\n") { p ->
                    val price = p.price?.let { "¥${agentMoney(it)}" } ?: "-"
                    "${p.code}｜${p.name}｜$price｜${if (p.purchasable) "在售" else "已下架"}"
                }
                "店铺商品（共 ${products.size} 件）：\n$lines"
            }
        },
    ),

    AgentToolSpec(
        name = AiToolName.LIST_CATEGORY_SCHEMES,
        definition = toolDef(
            AiToolName.LIST_CATEGORY_SCHEMES,
            "查看我店铺现有的分类方案（方案 → 分类 → 各分类商品数，含分类的数字 id）。" +
                "归类商品前先调用它拿 category_id。**每次重新调用获取最新数据。**",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, _ ->
            val schemes = apiCall { graph.merchantConsoleApi.schemes() }.items
            if (schemes.isEmpty()) {
                "还没有分类方案。可用 create_category_scheme 创建（如「吃穿住行用」）。"
            } else {
                schemes.joinToString("\n") { s ->
                    val cats = s.categories.flatMap { c ->
                        listOf("  - ${c.name}（category_id:${c.id}，${c.productCount ?: 0} 件）") +
                            c.children.map {
                                "    · ${it.name}（category_id:${it.id}，${it.productCount ?: 0} 件，二级，属「${c.name}」）"
                            }
                    }
                    val flags = listOfNotNull(
                        "默认".takeIf { s.isDefault },
                        "已隐藏".takeIf { s.isVisible == false },
                    ).joinToString("、")
                    "【${s.name}】${if (flags.isEmpty()) "" else "（$flags）"}\n" +
                        (if (cats.isEmpty()) "  （还没有分类）" else cats.joinToString("\n"))
                }
            }
        },
    ),

    AgentToolSpec(
        name = AiToolName.CREATE_CATEGORY_SCHEME,
        definition = toolDef(
            AiToolName.CREATE_CATEGORY_SCHEME,
            "创建一个分类方案及其分类（最多两级）。categories 两种格式任选：" +
                "①平铺逗号分隔「吃,穿,住」；②两级用 JSON 数组，如 " +
                "[{\"name\":\"吃\",\"children\":[\"零食\",\"生鲜\"]},{\"name\":\"穿\"}]。" +
                "创建后用 list_category_schemes 拿各分类的 category_id，" +
                "再用 assign_category_products 归类商品（一级/二级均可归类；" +
                "买家选一级自动含其二级商品）。",
            params = listOf(
                "name" to strParam("方案名，如「吃穿住行用」"),
                "categories" to strParam("分类列表：逗号分隔或两级 JSON 数组"),
            ),
            required = listOf("name", "categories"),
        ),
        kind = AiToolName.Kind.MUTATION,
        intentSummary = { args ->
            // 摘要要给人读 —— 原样回显 JSON 等于让用户在确认框里读代码。
            // 参数原文仍在 details 里（确认框的安全语义是「看到什么就执行什么」）。
            val raw = args.string("categories") ?: ""
            val desc = AgentSchemeInput.parse(raw)?.joinToString("、") { draft ->
                if (draft.children.isEmpty()) draft.name
                else "${draft.name}（含 ${draft.children.joinToString("/")}）"
            } ?: raw
            "创建分类方案「${args.string("name") ?: ""}」，包含分类：$desc"
        },
        run = { args, _ ->
            val name = args.string("name") ?: ""
            val drafts = AgentSchemeInput.parse(args.string("categories") ?: "")
            when {
                name.isEmpty() -> "缺少方案名。"
                drafts == null -> "缺少分类列表（逗号分隔或 JSON 数组）。"
                else -> {
                    // origin=ai 记方案来源，供 AI 分类溯源（与归类的 assignedBy 同一条线）
                    val scheme = apiCall {
                        graph.merchantConsoleApi.createScheme(CreateSchemeRequest(name, origin = "ai"))
                    }
                    for (draft in drafts) {
                        val created = apiCall {
                            graph.merchantConsoleApi.addSchemeCategory(scheme.id, AddCategoryRequest(draft.name))
                        }
                        for (child in draft.children) {
                            apiCall {
                                graph.merchantConsoleApi.addSchemeCategory(
                                    scheme.id,
                                    AddCategoryRequest(child, parentId = created.id),
                                )
                            }
                        }
                    }
                    // 回读一次拿 category_id —— 归类要用它，不回读模型就得再调一次 list
                    val fresh = apiCall { graph.merchantConsoleApi.schemes() }.items
                        .firstOrNull { it.id == scheme.id }
                    val catList = (fresh?.categories ?: emptyList()).joinToString("、") { c ->
                        val subs = c.children.joinToString("、") { "${it.name}（category_id:${it.id}）" }
                        "${c.name}（category_id:${c.id}${if (subs.isEmpty()) "" else "，子分类：$subs"}）"
                    }
                    "方案「$name」已创建。分类：$catList。" +
                        "接下来可用 assign_category_products 把商品归入各分类" +
                        "（每个分类调用一次、给全量 code）。"
                }
            }
        },
    ),

    AgentToolSpec(
        name = AiToolName.ASSIGN_CATEGORY_PRODUCTS,
        definition = toolDef(
            AiToolName.ASSIGN_CATEGORY_PRODUCTS,
            "把商品归入某个分类（**整体替换**语义：给该分类的全量商品 code，" +
                "没列出的会被移出该分类）。每个分类调用一次。" +
                "category_id 来自 list_category_schemes。",
            params = listOf(
                "category_id" to intParam("分类的数字 id"),
                "product_codes" to strParam("该分类的全量商品 code，逗号分隔"),
            ),
            required = listOf("category_id", "product_codes"),
        ),
        kind = AiToolName.Kind.MUTATION,
        intentSummary = { args ->
            val count = AgentSchemeInput.codes(args.string("product_codes") ?: "").size
            "归类到分类 #${args.int("category_id") ?: 0}：共 $count 件商品（整体替换）"
        },
        run = { args, _ ->
            val categoryId = args.int("category_id")
            if (categoryId == null) {
                "需要 category_id（分类的数字 id），可用 list_category_schemes 查。"
            } else {
                val codes = AgentSchemeInput.codes(args.string("product_codes") ?: "")
                apiCall {
                    graph.merchantConsoleApi.setCategoryProducts(
                        categoryId,
                        // assignedBy=ai 记归类来源，供 AI 分类溯源
                        SetCategoryProductsRequest(codes, assignedBy = "ai"),
                    )
                }
                "已把 ${codes.size} 件商品归入分类 #$categoryId（整体替换）。"
            }
        },
    ),
)

/**
 * 分类输入解析（对齐 iOS AgentSchemeInput）。
 *
 * 模型给分类列表有两种写法，都要吃：
 * - 平铺：「吃,穿,住」
 * - 两级：`[{"name":"吃","children":["零食","生鲜"]},{"name":"穿"}]`
 *
 * 强制它只用一种会平白增加出错面 —— 解析成本远低于让模型反复试。
 */
internal object AgentSchemeInput {

    data class Draft(val name: String, val children: List<String>)

    fun parse(raw: String): List<Draft>? {
        val trimmed = raw.trim()

        if (trimmed.startsWith("[")) {
            val array = runCatching { Json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull()
                ?: return null
            val out = array.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.asName()?.let { Draft(it, emptyList()) }
                    is JsonObject -> element["name"].asName()?.let { name ->
                        val children = (element["children"] as? JsonArray)
                            ?.mapNotNull { it.asName() }
                            ?: emptyList()
                        Draft(name, children)
                    }
                    else -> null
                }
            }
            return out.ifEmpty { null }
        }

        // 中英文逗号都认 —— 中文输入法下模型常打出「，」
        val flat = trimmed.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        return if (flat.isEmpty()) null else flat.map { Draft(it, emptyList()) }
    }

    fun codes(raw: String): List<String> =
        raw.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

    private fun JsonElement?.asName(): String? =
        (this as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
}

/** 接单视角的状态文案（站在代购人立场，与工作台语义一致；对齐 iOS OrderStatusText.agent） */
private fun claimStatusLabel(status: String): String = when (status.uppercase()) {
    "CLAIMED" -> "待买家支付"
    "PAID" -> "待采购"
    "PURCHASING" -> "采购中"
    "DELIVERING" -> "配送中"
    "DELIVERED" -> "待买家确认"
    "COMPLETED" -> "已完成"
    "CANCELLED" -> "已取消"
    "REFUNDED" -> "已退款"
    else -> status
}
