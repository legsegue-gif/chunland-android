package com.chunland.app

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.core.network.ChunlandJson
import com.chunland.app.core.network.camelizeKeys
import com.chunland.app.data.model.Cart
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 锁住购物车 wire 契约（样例为构造数据，仅锁 wire 结构）。
 * 注意混合 case：cartId/itemsTotal 是手工对象 camelCase，items 行是 SQL 裸行 snake_case；
 * itemsTotal 是服务端算好的**字符串**，端上只显示不加总。
 */
class CartModelsTest {

    private val cartSample = """
        {"code":0,"message":"ok","data":{"cartId":1,"items":[
          {"id":1,"product_code":"1000001","selected_size":null,"quantity":1,
           "merchant_id":1,"merchant_name":"示例商家","name":"示例商品",
           "unit_type":null,"random_weight":false,"min_order_quantity":1,
           "max_order_quantity":500,"current_price":100.0,"original_price":200.0,
           "stock_status":"inStock","thumbnail":"https://img.example.com/a.jpg"}
        ],"itemsTotal":"100.00"}}
    """.trimIndent()

    @Test
    fun `mixed-case cart response decodes after key normalization`() {
        val element = ChunlandJson.parseToJsonElement(cartSample).camelizeKeys()
        val envelope = ChunlandJson.decodeFromJsonElement<ApiEnvelope<Cart>>(element)

        val cart = envelope.data!!
        assertEquals(1, cart.cartId)
        assertEquals("100.00", cart.itemsTotal)   // 字符串原样保留

        val item = cart.items.single()
        assertEquals(1, item.id)
        assertEquals("1000001", item.productCode)
        assertNull(item.selectedSize)
        assertEquals(1, item.merchantId)
        assertEquals("示例商家", item.merchantName)
        assertEquals(1, item.minOrderQuantity)
        assertEquals(500, item.maxOrderQuantity)
        assertEquals(100.0, item.currentPrice!!, 0.0)
        assertEquals("inStock", item.stockStatus)
    }
}
