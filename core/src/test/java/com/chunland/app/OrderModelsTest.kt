package com.chunland.app

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.core.network.ChunlandJson
import com.chunland.app.data.model.OrderQuote
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁住报价 wire 契约（样例为构造数据，仅锁 wire 结构；全 camelCase 数字金额）。 */
class OrderModelsTest {

    @Test
    fun `quote response decodes`() {
        val json = """
            {"code":0,"message":"ok","data":{"groups":[
              {"merchantId":1,"merchantName":"示例商家","itemsTotal":300.0,
               "platformFee":0,"agentFee":0,"totalAmount":300.0,
               "meetsMinOrder":true,"minOrderAmount":0,"platformFeeRate":0}
            ],"grandTotal":300.0}}
        """.trimIndent()

        val envelope = ChunlandJson.decodeFromString<ApiEnvelope<OrderQuote>>(json)
        val quote = envelope.data!!
        assertEquals(300.0, quote.grandTotal, 0.0)
        val group = quote.groups.single()
        assertEquals(1, group.merchantId)
        assertEquals("示例商家", group.merchantName)
        assertEquals(300.0, group.itemsTotal, 0.0)
        assertTrue(group.meetsMinOrder)
    }
}
