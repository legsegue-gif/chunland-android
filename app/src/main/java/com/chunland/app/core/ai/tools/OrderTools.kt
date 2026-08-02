package com.chunland.app.core.ai.tools

import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.AiToolName
import com.chunland.app.core.ai.AiToolSpec
import com.chunland.app.core.ai.MutationIntent
import com.chunland.app.core.ai.PreparedMutation
import com.chunland.app.core.ai.aiMoney
import com.chunland.app.core.ai.argInt
import com.chunland.app.core.ai.argString
import com.chunland.app.core.ai.prop
import com.chunland.app.core.ai.toolSchema
import com.chunland.app.core.network.apiCall
import com.chunland.app.data.model.DeliveryAddress
import com.chunland.app.data.model.PlaceOrderRequest
import com.chunland.app.data.model.QuoteRequest
import com.chunland.app.data.model.orderStatusLabel

// 订单域 AI 工具：下单（mutation，走 HITL）+ 查我的订单 / 看某单详情（只读）。
// 订单的状态流转（确认收货 / 取消 / 退款等）敏感，暂不交给 AI（与 iOS 同押后）。
internal fun orderTools(graph: AppGraph): List<AiToolSpec> = listOf(

    AiToolSpec(
        name = AiToolName.PLACE_ORDER,
        tool = toolSchema(
            AiToolName.PLACE_ORDER,
            description = "用购物车当前内容下单。收货地址自动使用用户地址簿的默认地址、费用以服务端报价为准，" +
                "两者都会在确认弹窗中展示给用户 —— **不要向用户索要姓名/电话/地址，也不要自行报费用**。" +
                "用户没有地址时引导其到「我的 → 地址管理」添加；想换地址时告知其到购物车结算页选择。",
            properties = mapOf("note" to prop("string", "配送备注（可选）")),
        ),
        kind = AiToolName.Kind.MUTATION,
        // 执行期解析（PreparedMutation，对齐 iOS）：地址取地址簿、费用取服务端报价（与结算页
        // 同一 quote 端点，含距离代购费与起送校验），确认框展示并执行的都是这份快照。
        // 模型全程接触不到地址明细 —— 既堵住「让用户口述地址 → areaCode 丢失 → 距离费
        // 静默为 0」的计费旁路，地址簿 PII 也不进模型上下文。
        prepare = { args, _ ->
            val addresses = apiCall { graph.addressApi.list() }
            if (addresses.isEmpty()) {
                PreparedMutation.Abort(
                    "用户的地址簿还没有收货地址，无法下单。请引导用户到「我的 → 地址管理」" +
                        "添加收货地址后再试；不要让用户在对话里口述地址。",
                )
            } else {
                val chosen = addresses.firstOrNull { it.isDefault } ?: addresses.first()
                val quote = apiCall { graph.orderApi.quote(QuoteRequest(areaCode = chosen.areaCode)) }
                val short = quote.groups.firstOrNull { !it.meetsMinOrder }
                if (short != null) {
                    PreparedMutation.Abort(
                        "「${short.merchantName}」商品小计 ¥${aiMoney(short.itemsTotal)} 未达起送金额 " +
                            "¥${aiMoney(short.minOrderAmount)}，无法下单。可建议用户补足该店商品，" +
                            "或从购物车移除该店商品后再下单。",
                    )
                } else {
                    val delivery = DeliveryAddress(
                        name = chosen.name,
                        phone = chosen.phone,
                        address = chosen.address,
                        note = args.argString("note"),
                        areaCode = chosen.areaCode,
                    )
                    val fees = quote.groups.joinToString("\n") { g ->
                        val label = if (quote.groups.size == 1) "费用" else g.merchantName
                        "$label：商品 ¥${aiMoney(g.itemsTotal)} + 平台费 ¥${aiMoney(g.platformFee)} + 代购费 ¥${aiMoney(g.agentFee)}"
                    }
                    val multi = if (quote.groups.size > 1) "（${quote.groups.size} 个商家，分别成单）" else ""
                    val summary =
                        "AI 想用购物车内容下单$multi\n" +
                            "收货：${chosen.name} ${chosen.phone}${if (chosen.isDefault) "（默认地址）" else ""}\n" +
                            "地址：${chosen.address}\n" +
                            "$fees\n合计 ¥${aiMoney(quote.grandTotal)}"
                    PreparedMutation.Ready(
                        intent = MutationIntent(
                            AiToolName.PLACE_ORDER,
                            summary,
                            mapOf("address_id" to "${chosen.id}", "grand_total" to "${quote.grandTotal}"),
                        ),
                    ) {
                        val batch = apiCall { graph.orderApi.place(PlaceOrderRequest(deliveryAddress = delivery)) }
                        val dest = "收货地址用的是用户确认过的地址簿地址（${chosen.name}，${chosen.address}）"
                        val only = batch.orders.firstOrNull()
                        if (batch.orderCount == 1 && only != null) {
                            "下单成功！订单号：${only.orderNumber}，总金额：¥${aiMoney(only.totalAmount)}，等待代购人接单。$dest。"
                        } else {
                            "下单成功！已按商家拆为 ${batch.orderCount} 笔订单，合计 ¥${batch.grandTotal}，等待代购人接单。$dest。"
                        }
                    }
                }
            }
        },
        // 不可达：place_order 恒经 prepare 的确认流程执行（executeTool 优先走 prepare）
        run = { _, _ -> "内部错误：place_order 只能经确认流程执行。" },
    ),

    AiToolSpec(
        name = AiToolName.LIST_MY_ORDERS,
        tool = toolSchema(
            AiToolName.LIST_MY_ORDERS,
            description = "查看「我的订单」列表（订单号、状态、金额）。问及订单进度/历史时调用。**每次都重新调用获取最新状态，禁止复用历史结果。**",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, _ ->
            val orders = apiCall { graph.orderApi.list() }
            if (orders.isEmpty()) {
                "你还没有订单。"
            } else {
                val lines = orders.take(20).joinToString("\n") { o ->
                    val cnt = o.itemCount?.let { "${it}件" } ?: ""
                    "#${o.orderNumber}（id:${o.id}）｜${orderStatusLabel(o.status)}｜¥${aiMoney(o.totalAmount)}｜$cnt"
                }
                "你的订单（共 ${orders.size} 笔）：\n$lines\n（需要某单详情时用 get_order_detail，order_id 传上面的 id）"
            }
        },
    ),

    AiToolSpec(
        name = AiToolName.GET_ORDER_DETAIL,
        tool = toolSchema(
            AiToolName.GET_ORDER_DETAIL,
            description = "查看某一订单的详情（状态、金额构成、商品、收货信息、当前可执行的操作）。order_id 来自 list_my_orders 的 id 或当前页面上下文。**每次重新调用获取最新状态。**",
            properties = mapOf("order_id" to prop("integer", "订单的数字 id")),
            required = listOf("order_id"),
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { args, _ ->
            val oid = args.argInt("order_id")
                ?: return@AiToolSpec "请提供 order_id（订单的数字 id）。可先用 list_my_orders 查到 id。"
            val o = apiCall { graph.orderApi.detail(oid) }
            var s = """
            订单 #${o.orderNumber}
            状态：${orderStatusLabel(o.status)}
            合计：¥${aiMoney(o.totalAmount)}（商品 ¥${aiMoney(o.itemsTotal)} + 平台费 ¥${aiMoney(o.platformFee)} + 代购费 ¥${aiMoney(o.agentFee)}）
            商品：${o.items.joinToString("、") { "${it.productSnapshot.name} × ${it.quantity}" }}
            收货：${o.deliveryAddress.name} ${o.deliveryAddress.address}
            下单时间：${o.createdAt}
            """.trimIndent()
            val actions = o.availableActions
            if (!actions.isNullOrEmpty()) {
                s += "\n你现在可以：${actions.joinToString("、") { it.label }}"
            }
            s
        },
    ),
)
