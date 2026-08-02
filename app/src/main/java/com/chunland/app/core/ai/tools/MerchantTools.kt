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
import com.chunland.app.data.model.AddCategoryRequest
import com.chunland.app.data.model.CreateSchemeRequest
import com.chunland.app.data.model.SetCategoryProductsRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// 商家域 AI 工具（AI 分类路线 A）：读店铺商品/方案（只读）+ 建方案/归类（mutation，走 HITL）。
// AI 只在编辑期参与：生成建议 → 确认弹窗 → 普通 REST 落库；消费者浏览读的是 DB，与 AI 无关。
// 仅商家身份可用（AiToolName.allowedIdentities 门控：下发+执行两道），服务端 requireRole('merchant') 双保险。
internal fun merchantTools(graph: AppGraph): List<AiToolSpec> = listOf(

    AiToolSpec(
        name = AiToolName.LIST_STORE_PRODUCTS,
        tool = toolSchema(
            AiToolName.LIST_STORE_PRODUCTS,
            description = "读取我店铺的全部商品（code、名称、价格、上架状态）。做分类归类前必须先调用它拿到商品清单。**每次重新调用获取最新数据。**",
        ),
        kind = AiToolName.Kind.READ_ONLY,
        run = { _, _ ->
            val products = apiCall { graph.merchantConsoleApi.products() }.items
            if (products.isEmpty()) {
                "店里还没有商品。"
            } else {
                val lines = products.joinToString("\n") { p ->
                    val price = p.price?.let { "¥${aiMoney(it)}" } ?: "-"
                    "${p.code}｜${p.name}｜$price｜${if (p.purchasable) "在售" else "已下架"}"
                }
                "店铺商品（共 ${products.size} 件）：\n$lines"
            }
        },
    ),

    AiToolSpec(
        name = AiToolName.LIST_CATEGORY_SCHEMES,
        tool = toolSchema(
            AiToolName.LIST_CATEGORY_SCHEMES,
            description = "查看我店铺现有的分类方案（方案 → 分类 → 各分类商品数，含分类的数字 id）。归类商品前先调用它拿 category_id。**每次重新调用获取最新数据。**",
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

    AiToolSpec(
        name = AiToolName.CREATE_CATEGORY_SCHEME,
        tool = toolSchema(
            AiToolName.CREATE_CATEGORY_SCHEME,
            description = "创建一个分类方案及其分类（最多两级）。categories 两种格式任选：①平铺逗号分隔「吃,穿,住」；" +
                "②两级用 JSON 数组，如 [{\"name\":\"吃\",\"children\":[\"零食\",\"生鲜\"]},{\"name\":\"穿\"}]。" +
                "创建后用 list_category_schemes 拿各分类的 category_id，再用 assign_category_products 归类商品" +
                "（一级/二级均可归类；买家选一级自动含其二级商品）。",
            properties = mapOf(
                "name" to prop("string", "方案名（≤20 字），如「吃穿住行用」"),
                "categories" to prop("string", "分类列表：逗号分隔平铺，或 JSON 数组表达两级（每个名 ≤20 字，两级合计最多 30 个）"),
            ),
            required = listOf("name", "categories"),
        ),
        kind = AiToolName.Kind.MUTATION,
        intentSummary = { args ->
            val name = args.argString("name") ?: ""
            val raw = args.argString("categories") ?: ""
            val desc = parseSchemeCatDrafts(raw)?.joinToString("、") { d ->
                if (d.children.isEmpty()) d.name else "${d.name}（含 ${d.children.joinToString("/")}）"
            } ?: raw
            "AI 想创建分类方案「$name」，包含分类：$desc"
        },
        run = { args, _ ->
            val name = args.argString("name") ?: return@AiToolSpec "缺少方案名。"
            val drafts = parseSchemeCatDrafts(args.argString("categories") ?: "")
                ?: return@AiToolSpec "缺少分类列表（逗号分隔或 JSON 数组）。"
            val scheme = apiCall { graph.merchantConsoleApi.createScheme(CreateSchemeRequest(name)) }
            for (d in drafts) {
                val created = apiCall { graph.merchantConsoleApi.addSchemeCategory(scheme.id, AddCategoryRequest(d.name)) }
                for (child in d.children) {
                    apiCall {
                        graph.merchantConsoleApi.addSchemeCategory(scheme.id, AddCategoryRequest(child, parentId = created.id))
                    }
                }
            }
            val fresh = apiCall { graph.merchantConsoleApi.schemes() }.items.firstOrNull { it.id == scheme.id }
            val catList = (fresh?.categories ?: emptyList()).joinToString("、") { c ->
                val subs = c.children.joinToString("、") { "${it.name}（category_id:${it.id}）" }
                "${c.name}（category_id:${c.id}${if (subs.isEmpty()) "" else "，子分类：$subs"}）"
            }
            "方案「$name」已创建。分类：$catList。" +
                "接下来可用 assign_category_products 把商品归入各分类（每个分类调用一次、给全量 code）。"
        },
    ),

    AiToolSpec(
        name = AiToolName.ASSIGN_CATEGORY_PRODUCTS,
        tool = toolSchema(
            AiToolName.ASSIGN_CATEGORY_PRODUCTS,
            description = "把商品归入某个方案分类（**整体替换**该分类下的商品，一次给全量）。category_id 来自 list_category_schemes；product_codes 来自 list_store_products。每个分类调用一次。",
            properties = mapOf(
                "category_id" to prop("integer", "分类的数字 id"),
                "product_codes" to prop("string", "商品 code 列表，逗号分隔（该分类的全量商品）"),
            ),
            required = listOf("category_id", "product_codes"),
        ),
        kind = AiToolName.Kind.MUTATION,
        intentSummary = { args ->
            val count = (args.argString("product_codes") ?: "").split(',', '，').count { it.isNotBlank() }
            "AI 想把 $count 个商品归入分类 #${args.argInt("category_id") ?: 0}（整体替换该分类）"
        },
        run = { args, _ ->
            val catId = args.argInt("category_id")
                ?: return@AiToolSpec "缺少 category_id（先用 list_category_schemes 查）。"
            val codes = (args.argString("product_codes") ?: "")
                .split(',', '，')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            apiCall { graph.merchantConsoleApi.setCategoryProducts(catId, SetCategoryProductsRequest(codes)) }
            "已把 ${codes.size} 个商品归入分类 #$catId。"
        },
    ),
)

// create_category_scheme 的 categories 参数解析：JSON 数组（两级）优先，逗号分隔平铺兜底（旧契约兼容）。
// JSON 元素可以是字符串（一级）或 {name, children:[String]}（一级+二级）。
internal data class SchemeCatDraft(val name: String, val children: List<String>)

private fun JsonElement?.asName(): String? =
    (this as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

internal fun parseSchemeCatDrafts(raw: String): List<SchemeCatDraft>? {
    val trimmed = raw.trim()
    if (trimmed.startsWith("[")) {
        val arr = runCatching { Json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull() ?: return null
        val out = arr.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.asName()?.let { SchemeCatDraft(it, emptyList()) }
                is JsonObject -> el["name"].asName()?.let { n ->
                    SchemeCatDraft(n, (el["children"] as? JsonArray)?.mapNotNull { it.asName() } ?: emptyList())
                }
                else -> null
            }
        }
        return out.ifEmpty { null }
    }
    val flat = trimmed.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
    return if (flat.isEmpty()) null else flat.map { SchemeCatDraft(it, emptyList()) }
}
