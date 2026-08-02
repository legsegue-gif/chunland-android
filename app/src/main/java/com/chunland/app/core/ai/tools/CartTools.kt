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
import com.chunland.app.data.model.AddCartItemRequest

// 购物车域 AI 工具：加购（mutation，走 HITL）/ 查看（只读）。
internal fun cartTools(graph: AppGraph): List<AiToolSpec> = listOf(

    AiToolSpec(
        name = AiToolName.ADD_TO_CART,
        tool = toolSchema(
            AiToolName.ADD_TO_CART,
            description = "将指定商品加入购物车",
            properties = mapOf(
                "product_code" to prop("string", "商品代码"),
                "quantity" to prop("integer", "数量，默认1"),
            ),
            required = listOf("product_code"),
        ),
        kind = AiToolName.Kind.MUTATION,
        intentSummary = { args ->
            val code = args.argString("product_code") ?: ""
            val qty = args.argInt("quantity") ?: 1
            "AI 想把商品 $code × $qty 加入购物车"
        },
        run = { args, _ ->
            val code = args.argString("product_code") ?: return@AiToolSpec "缺少 product_code。"
            val qty = args.argInt("quantity") ?: 1
            apiCall { graph.cartApi.addItem(AddCartItemRequest(productCode = code, quantity = qty)) }
            "已将商品 $code × $qty 加入购物车"
        },
    ),

    AiToolSpec(
        name = AiToolName.GET_CART,
        tool = toolSchema(
            AiToolName.GET_CART,
            description = "查看当前购物车内容和总价。**每次询问购物车都必须重新调用，禁止复用历史结果**（用户可能在中间加/删了商品）。",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, _ ->
            val cart = apiCall { graph.cartApi.get() }
            if (cart.items.isEmpty()) {
                "购物车是空的"
            } else {
                val lines = cart.items.joinToString("\n") {
                    "${it.name} × ${it.quantity}  ¥${aiMoney(it.currentPrice)}"
                }
                "购物车（共 ${cart.items.size} 种商品）：\n$lines\n合计：¥${cart.itemsTotal}"
            }
        },
    ),
)
