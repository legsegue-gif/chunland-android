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

// 商品域 AI 工具：搜索 / 详情 / 分类。全只读，直接执行。
// 搜索与分类感知 AiToolScope：进店上下文（scope.merchantId 非空）时硬限定到该店 ——
// 「本店搜索/本店分类」由代码兑现，不靠 prompt 许愿（否则模型会把全局数据当本店数据）。
internal fun productTools(graph: AppGraph): List<AiToolSpec> = listOf(

    AiToolSpec(
        name = AiToolName.SEARCH_PRODUCTS,
        tool = toolSchema(
            AiToolName.SEARCH_PRODUCTS,
            description = "搜索商品列表，支持关键词和分类筛选（店铺上下文中自动限定在当前店铺内）",
            properties = mapOf(
                "query" to prop("string", "搜索关键词，如\"牛排\"\"咖啡\""),
                "category" to prop("string", "分类代码，如 CN141401"),
                "limit" to prop("integer", "返回数量，默认10，最多20"),
            ),
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { args, scope ->
            val limit = (args.argInt("limit") ?: 10).coerceIn(1, 20)
            val resp = apiCall {
                graph.productApi.list(
                    limit = limit,
                    category = args.argString("category"),
                    keyword = args.argString("query"),
                    merchant = scope.merchantId,
                )
            }
            val where = scope.merchantName?.let { "在「$it」店内" } ?: ""
            if (resp.items.isEmpty()) {
                "${where}没有找到匹配的商品。"
            } else {
                val shown = resp.items.take(limit)
                val summary = shown.joinToString("\n") {
                    "[${it.code}] ${it.name} ¥${aiMoney(it.currentPrice)} ${if (it.outOfStock) "缺货" else "有货"}"
                }
                "${where}找到 ${resp.pagination.total} 个商品（展示前 ${shown.size} 个）：\n$summary"
            }
        },
    ),

    AiToolSpec(
        name = AiToolName.GET_PRODUCT_DETAIL,
        tool = toolSchema(
            AiToolName.GET_PRODUCT_DETAIL,
            description = "获取指定商品的详细信息（价格、库存、图片等）",
            properties = mapOf("code" to prop("string", "商品代码，如 123456")),
            required = listOf("code"),
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { args, _ ->
            val code = args.argString("code") ?: return@AiToolSpec "请提供商品代码 code。"
            val p = apiCall { graph.productApi.detail(code) }
            """
            商品：${p.name}
            价格：¥${aiMoney(p.currentPrice)}
            库存：${if (p.outOfStock) "缺货" else p.stockStatus ?: "未知"}
            单位：${p.unitType ?: "-"}
            随机重量：${if (p.randomWeight) "是（按实重计价）" else "否"}
            """.trimIndent()
        },
    ),

    AiToolSpec(
        name = AiToolName.GET_CATEGORIES,
        tool = toolSchema(
            AiToolName.GET_CATEGORIES,
            description = "获取商品分类，用于了解有哪些品类（店铺上下文中返回当前店铺自己的分类）",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, scope ->
            val cats = apiCall { graph.categoryApi.tree(merchant = scope.merchantId) }
            if (scope.merchantId != null) {
                // 进店：返回该店自有分类；店无分类导航时如实说，绝不回落全局树冒充本店分类
                val label = scope.merchantName?.let { "「$it」" } ?: "该店铺"
                if (cats.isEmpty()) {
                    "${label}没有分类导航，可用 search_products 直接搜索店内商品。"
                } else {
                    "${label}的分类：" + cats.take(15).joinToString("、") { "${it.name}（${it.code}）" }
                }
            } else {
                cats.filter { it.level == 1 }.take(10).joinToString("、") { "${it.name}（${it.code}）" }
            }
        },
    ),
)
