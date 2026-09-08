package com.chunland.app

import com.chunland.app.ui.OTP_LENGTH
import com.chunland.app.ui.sanitizeOtp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证码输入归一（对齐 iOS 三处 `.onChange` 里的 `v.filter(\.isNumber).prefix(6)`）。
 *
 * 三处原本各写各的：登录页压根没过滤，绑定页没设数字键盘。抽成一个函数 + 这组测试
 * 就是为了别再漂移 —— 粘贴带空格/短信整句时的行为必须三处一致。
 */
class OtpInputTest {

    @Test
    fun `只留数字`() {
        assertEquals("123456", sanitizeOtp("1a2b3c4d5e6f"))
        assertEquals("123456", sanitizeOtp("123 456"))
        assertEquals("", sanitizeOtp("abcdef"))
    }

    @Test
    fun `最多六位`() {
        assertEquals("123456", sanitizeOtp("1234567890"))
        assertEquals(OTP_LENGTH, sanitizeOtp("99999999999").length)
    }

    @Test
    fun `从短信整句里粘贴也能取到码`() {
        // 用户直接长按粘贴整条短信是常见操作；过滤后恰好剩验证码才算可用
        assertEquals("654321", sanitizeOtp("Chunland code: 654321"))
    }

    @Test
    fun `空输入与不足六位原样保留`() {
        assertEquals("", sanitizeOtp(""))
        assertEquals("12", sanitizeOtp("12"))
    }
}
