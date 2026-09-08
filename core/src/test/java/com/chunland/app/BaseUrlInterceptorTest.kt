package com.chunland.app

import com.chunland.app.core.network.BaseUrlInterceptor.Companion.rewrite
import com.chunland.app.core.network.ServerConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 基址改写的拼接规则。
 *
 * 这段逻辑坏掉时表现是「请求 404 / 参数丢失」，从 UI 上看不出根因，所以必须有测试盯着。
 * 尤其是 `/api/v1` 前缀的保留 —— Retrofit 的 baseUrl 换成哨兵后，前缀完全靠这里补回来。
 */
class BaseUrlInterceptorTest {

    /** Retrofit 用哨兵 baseUrl 解析相对路径后得到的样子（接口路径一律无前导斜杠）。 */
    private fun sentinel(pathAndQuery: String) = "http://chunland.invalid$pathAndQuery".toHttpUrl()

    @Test
    fun `重挂到含 api-v1 前缀的基址`() {
        val out = rewrite("http://10.0.2.2:3001/api/v1", sentinel("/auth/login"))
        assertEquals("http://10.0.2.2:3001/api/v1/auth/login", out.toString())
    }

    @Test
    fun `查询串原样保留`() {
        val out = rewrite("http://10.0.2.2:3000/api/v1", sentinel("/feed?mode=foryou&limit=20"))
        assertEquals("http://10.0.2.2:3000/api/v1/feed?mode=foryou&limit=20", out.toString())
    }

    @Test
    fun `切到 https 与默认端口`() {
        val out = rewrite("https://example.com/api/v1", sentinel("/products/123"))
        assertEquals("https://example.com/api/v1/products/123", out.toString())
    }

    @Test
    fun `基址末尾有无斜杠结果一致`() {
        val a = rewrite("http://h:3000/api/v1", sentinel("/cart"))
        val b = rewrite("http://h:3000/api/v1/", sentinel("/cart"))
        assertEquals("http://h:3000/api/v1/cart", a.toString())
        assertEquals(a.toString(), b.toString())
    }

    @Test
    fun `多层路径与路径参数`() {
        val out = rewrite("http://10.0.2.2:3001/api/v1", sentinel("/orders/65/evidences"))
        assertEquals("http://10.0.2.2:3001/api/v1/orders/65/evidences", out.toString())
    }

    @Test
    fun `已编码的路径段不被二次编码`() {
        // 媒体 key 里可能带百分号编码；再编码一次会变成 %2520 打不到东西
        val out = rewrite("http://10.0.2.2:3001/api/v1", sentinel("/media/merchant/a%20b.jpg"))
        assertEquals("http://10.0.2.2:3001/api/v1/media/merchant/a%20b.jpg", out.toString())
    }

    @Test
    fun `基址不含路径前缀时也能挂`() {
        val out = rewrite("http://10.0.2.2:3001", sentinel("/auth/login"))
        assertEquals("http://10.0.2.2:3001/auth/login", out.toString())
    }

    @Test
    fun `基址不可解析返回 null 让调用方原样放行`() {
        assertNull(rewrite("not-a-url", sentinel("/auth/login")))
        assertNull(rewrite("", sentinel("/auth/login")))
        assertNull(rewrite("ftp://h/api/v1", sentinel("/auth/login")))
    }

    // ── ServerConfig.normalize：入口处的校验规则，与上面的兜底是同一件事的两端 ──

    @Test
    fun `normalize 接受合法地址并去掉尾斜杠与空白`() {
        assertEquals(
            "http://10.0.2.2:3000/api/v1",
            ServerConfig.normalize("  http://10.0.2.2:3000/api/v1/  "),
        )
        assertEquals("https://example.com/api/v1", ServerConfig.normalize("https://example.com/api/v1"))
    }

    @Test
    fun `normalize 拒绝非法输入`() {
        assertNull(ServerConfig.normalize("not-a-url"))
        assertNull(ServerConfig.normalize(""))
        assertNull(ServerConfig.normalize("10.0.2.2:3000"))
        assertNull(ServerConfig.normalize("ftp://example.com"))
    }
}
