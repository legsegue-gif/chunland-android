package com.chunland.app.core.ai.tools

import com.chunland.app.core.AppGraph
import com.chunland.app.core.ai.AiToolName
import com.chunland.app.core.ai.AiToolSpec
import com.chunland.app.core.ai.aiMoney
import com.chunland.app.core.ai.argInt
import com.chunland.app.core.ai.argString
import com.chunland.app.core.ai.prop
import com.chunland.app.core.ai.toolSchema
import com.chunland.app.core.network.apiCall
import com.chunland.app.data.model.AdjustmentDetail
import com.chunland.app.data.model.AdjustmentItem
import com.chunland.app.data.model.ProposeAdjustmentRequest
import com.chunland.app.data.model.settlementStatusLabel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// 代购域 AI 工具：接单进度 / 合并采购清单 / 结算收益（只读）+ 缺货改单起草（mutation，走 HITL）。
// 仅代购身份可用（AiToolName.allowedIdentities 门控：下发+执行两道），服务端 requireRole('agent') 双保险。
internal fun agentTools(graph: AppGraph): List<AiToolSpec> = listOf(

    AiToolSpec(
        name = AiToolName.LIST_MY_CLAIMS,
        tool = toolSchema(
            AiToolName.LIST_MY_CLAIMS,
            description = "查看我（代购人）的接单进度：待办分组计数 + 接单列表（订单号、状态、金额）。可选 status 只看某一状态。**每次重新调用获取最新状态，禁止复用历史结果。**",
            properties = mapOf(
                "status" to prop(
                    "string", "可选。只看某状态的接单",
                    enum = listOf("CLAIMED", "PAID", "PURCHASING", "DELIVERING", "DELIVERED"),
                ),
            ),
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { args, _ ->
            val status = args.argString("status")
            val (d, orders) = coroutineScope {
                val dash = async { apiCall { graph.agentProfileApi.dashboard() } }
                val list = async { apiCall { graph.orderApi.list(status = status, scope = "mine") } }
                dash.await() to list.await()
            }
            val c = d.counts
            var s = "待办概览：待买家支付 ${c.claimed}｜待采购 ${c.paid}｜采购中 ${c.purchasing}" +
                "（缺小票 ${c.purchasingNoReceipt}、改单待答复 ${c.purchasingPendingAdjustment}）｜" +
                "配送中 ${c.delivering}｜待买家确认 ${c.delivered}"
            if (orders.isEmpty()) {
                s += if (status != null) "\n该状态下暂无订单。" else "\n还没有接过单。"
            } else {
                val lines = orders.take(20).joinToString("\n") { o ->
                    "#${o.orderNumber}（id:${o.id}）｜${claimStatusLabel(o.status)}｜¥${aiMoney(o.totalAmount)}"
                }
                s += "\n接单列表（共 ${orders.size} 笔）：\n$lines\n（某单详情用 get_order_detail，order_id 传上面的 id）"
            }
            s
        },
    ),

    AiToolSpec(
        name = AiToolName.BUILD_PURCHASE_LIST,
        tool = toolSchema(
            AiToolName.BUILD_PURCHASE_LIST,
            description = "整理合并采购清单：把我待采购/采购中的订单按商家分组、同商品跨单聚合数量，并标注各单小票凭证状态。进店采购前调用。**每次重新调用获取最新数据。**",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, _ ->
            val list = apiCall { graph.agentProfileApi.purchaseList() }
            if (list.groups.isEmpty()) {
                "当前没有待采购的订单（待采购/采购中状态才会进清单）。"
            } else {
                list.groups.joinToString("\n\n") { g ->
                    val items = g.items.joinToString("\n") { it0 ->
                        val size = it0.selectedSize?.let { "（尺码 $it）" } ?: ""
                        val from = it0.breakdown.joinToString("、") { "…${it.orderNumber.takeLast(6)} ×${it.quantity}" }
                        "· ${it0.name}$size ×${it0.totalQuantity}（来自 $from）"
                    }
                    val receipts = g.orders.joinToString("、") { o ->
                        "…${o.orderNumber.takeLast(6)}（id:${o.id}）${if (o.hasReceipt) "已传小票" else "待传小票"}"
                    }
                    "【${g.merchantName}】${g.orders.size} 单 ${g.items.size} 种商品\n$items\n小票状态：$receipts"
                }
            }
        },
    ),

    AiToolSpec(
        name = AiToolName.SUMMARIZE_SETTLEMENTS,
        tool = toolSchema(
            AiToolName.SUMMARIZE_SETTLEMENTS,
            description = "查看我（代购人）的结算收益：待结算/已结算总额 + 最近结算明细（每单的货款返还、代购费、平台费）。问及收入/结算/某单赚多少时调用。**每次重新调用获取最新数据。**",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, _ ->
            val s = apiCall { graph.agentProfileApi.settlements() }
            var out = "待结算 ¥${aiMoney(s.pendingTotal)}｜已结算 ¥${aiMoney(s.paidTotal)}"
            if (s.items.isEmpty()) {
                out += "\n还没有结算记录（订单完成后自动记账）。"
            } else {
                val lines = s.items.take(15).joinToString("\n") { r ->
                    "#${r.orderNumber}｜应结 ¥${aiMoney(r.netPayable)}（货款 ¥${aiMoney(r.itemsReimburse)} + 代购费 ¥${aiMoney(r.agentFee)}）｜${settlementStatusLabel(r.status)}"
                }
                out += "\n最近结算（共 ${s.items.size} 笔）：\n$lines"
            }
            out
        },
    ),

    AiToolSpec(
        name = AiToolName.PROPOSE_ADJUSTMENT,
        tool = toolSchema(
            AiToolName.PROPOSE_ADJUSTMENT,
            description = "缺货改单：对采购中的某个订单商品发起「缺货移除」或「减量」，提交后由买家确认。order_id/order_item_id 先用 get_order_detail 查到。MVP 只支持下调，不能加价加量。",
            properties = mapOf(
                "order_id" to prop("integer", "订单的数字 id"),
                "order_item_id" to prop("integer", "订单内商品条目的数字 id（get_order_detail 可查）"),
                "action" to prop(
                    "string", "remove=缺货整项移除；reduce_qty=按缺货数量下调",
                    enum = listOf("remove", "reduce_qty"),
                ),
                "new_quantity" to prop("integer", "action=reduce_qty 时必填：下调后的数量（须小于原数量）"),
                "note" to prop("string", "给买家看的说明（可选），如「到店只剩 1 件」"),
            ),
            required = listOf("order_id", "order_item_id", "action"),
        ),
        kind = AiToolName.Kind.MUTATION,
        intentSummary = { args ->
            val action = args.argString("action") ?: ""
            val desc = if (action == "remove") {
                "缺货移除商品条目 #${args.argInt("order_item_id") ?: 0}"
            } else {
                "商品条目 #${args.argInt("order_item_id") ?: 0} 数量下调为 ${args.argInt("new_quantity") ?: 0}"
            }
            "AI 想对订单 #${args.argInt("order_id") ?: 0} 发起缺货改单：$desc，提交后需买家确认"
        },
        run = { args, _ ->
            val orderId = args.argInt("order_id")
            val itemId = args.argInt("order_item_id")
            val action = args.argString("action")
            if (orderId == null || itemId == null || action !in listOf("remove", "reduce_qty")) {
                return@AiToolSpec "参数不全：需要 order_id、order_item_id 和 action（remove/reduce_qty）。可先用 get_order_detail 查条目 id。"
            }
            val newQuantity = args.argInt("new_quantity")
            if (action == "reduce_qty" && newQuantity == null) {
                return@AiToolSpec "action=reduce_qty 时必须提供 new_quantity（下调后的数量）。"
            }
            val adj = apiCall {
                graph.orderApi.proposeAdjustment(
                    orderId,
                    ProposeAdjustmentRequest(
                        kind = "out_of_stock",
                        detail = AdjustmentDetail(
                            items = listOf(AdjustmentItem(orderItemId = itemId, action = action!!, newQuantity = newQuantity)),
                        ),
                        note = args.argString("note"),
                    ),
                )
            }
            "改单已提交（金额变化 ¥${aiMoney(adj.amountDelta)}），等待买家确认。买家接受后差额自动部分退款。"
        },
    ),
)

// 接单视角的状态文案（与工作台语义一致，站在代购人立场；对齐 iOS claimStatusLabel）
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
