package com.chunland.app

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.core.network.ChunlandJson
import com.chunland.app.core.network.camelizeKeys
import com.chunland.app.core.network.snakeToCamel
import com.chunland.app.data.model.ProductListResponse
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住「snake_case 裸行响应 → 键归一化 → camelCase DTO」链路。
 * 样例为构造数据，仅锁 wire 结构（键名与真实响应一致）。
 */
class ProductModelsTest {

    private val listSample = """
        {"code":0,"message":"ok","data":{"items":[
          {"code":"1000001","name":"示例商品","english_name":"Sample Product",
           "unit_type":null,"weight":null,"random_weight":false,
           "min_order_quantity":1,"max_order_quantity":500,
           "current_price":100.0,"original_price":200.0,"price_per_unit":null,
           "discount_amount":50,"stock_status":"inStock",
           "thumbnail":"https://img.example.com/a.jpg"}
        ],"pagination":{"page":1,"limit":2,"total":2,"totalPages":1}}}
    """.trimIndent()

    @Test
    fun `snake_case list response decodes after key normalization`() {
        val element = ChunlandJson.parseToJsonElement(listSample).camelizeKeys()
        val envelope = ChunlandJson.decodeFromJsonElement<ApiEnvelope<ProductListResponse>>(element)

        val item = envelope.data!!.items.single()
        assertEquals("1000001", item.code)
        assertEquals("Sample Product", item.englishName)
        assertNull(item.unitType)
        assertEquals(1, item.minOrderQuantity)
        assertEquals(500, item.maxOrderQuantity)
        assertEquals(100.0, item.currentPrice!!, 0.0)
        assertEquals(50.0, item.discountAmount!!, 0.0)
        assertEquals("inStock", item.stockStatus)
        // pagination 是手工构建对象，本就 camelCase —— 归一化必须无副作用
        assertEquals(1, envelope.data!!.pagination.totalPages)
    }

    @Test
    fun `key normalization only touches snake segments`() {
        assertEquals("englishName", "english_name".snakeToCamel())
        assertEquals("totalPages", "totalPages".snakeToCamel())   // camelCase 透传
        assertEquals("CN15", "CN15".snakeToCamel())               // 数据键不受影响
        assertEquals("a_1", "a_1".snakeToCamel())                 // 下划线后非小写字母不转
    }

    @Test
    fun `normalization recurses into nested arrays and objects`() {
        val element = ChunlandJson.parseToJsonElement(
            """{"outer_key":[{"inner_key":{"deep_key":1}}]}"""
        ).camelizeKeys()
        val text = element.jsonObject.toString()
        assertTrue(text.contains("outerKey"))
        assertTrue(text.contains("innerKey"))
        assertTrue(text.contains("deepKey"))
    }
}
