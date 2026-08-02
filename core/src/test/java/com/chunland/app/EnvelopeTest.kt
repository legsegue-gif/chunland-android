package com.chunland.app

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.core.network.ChunlandJson
import com.chunland.app.data.model.AuthResult
import com.chunland.app.data.model.OtpSendResult
import com.chunland.app.data.model.RefreshRequest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住 wire 契约：信封结构 + **双向 camelCase**（
 * 样例即真实响应）。配错全线皆错 —— 谁想加 naming strategy，先看这里的红灯。
 */
class EnvelopeTest {

    @Test
    fun `decodes camelCase envelope (real server response shape)`() {
        // 实测 POST /auth/otp/login 的响应结构
        val json = """
            {"code":0,"message":"ok","data":{
                "userId":55,"roles":["consumer"],
                "accessToken":"at","refreshToken":"rt"
            }}
        """.trimIndent()

        val envelope = ChunlandJson.decodeFromString<ApiEnvelope<AuthResult>>(json)

        assertEquals(0, envelope.code)
        val data = envelope.data!!
        assertEquals(55, data.userId)
        assertEquals(listOf("consumer"), data.roles)
        assertEquals("at", data.accessToken)
        assertEquals("rt", data.refreshToken)
    }

    @Test
    fun `decodes otp send result`() {
        // 实测 POST /auth/otp/send 的响应结构
        val envelope = ChunlandJson.decodeFromString<ApiEnvelope<OtpSendResult>>(
            """{"code":0,"message":"验证码已发送","data":{"cooldown":60,"expiresIn":300}}"""
        )
        assertEquals(60, envelope.data!!.cooldown)
        assertEquals(300, envelope.data!!.expiresIn)
    }

    @Test
    fun `encodes request body to camelCase`() {
        val body = ChunlandJson.encodeToString(RefreshRequest(refreshToken = "rt"))
        assertTrue("应输出 camelCase 键：$body", body.contains("\"refreshToken\""))
        assertFalse("绝不输出 snake_case 键：$body", body.contains("refresh_token"))
    }

    @Test
    fun `error envelope with null data decodes`() {
        val envelope = ChunlandJson.decodeFromString<ApiEnvelope<AuthResult>>(
            """{"code":40001,"message":"验证码错误"}"""
        )
        assertEquals(40001, envelope.code)
        assertEquals("验证码错误", envelope.message)
        assertNull(envelope.data)
    }

    @Test
    fun `unknown keys are ignored`() {
        val envelope = ChunlandJson.decodeFromString<ApiEnvelope<AuthResult>>(
            """{"code":0,"message":"ok","extraField":1,"data":{
                "userId":1,"accessToken":"a","refreshToken":"r","futureField":true}}"""
        )
        assertEquals(1, envelope.data!!.userId)
        assertNull(envelope.data!!.roles)
    }
}
