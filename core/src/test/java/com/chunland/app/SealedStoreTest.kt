package com.chunland.app

import com.chunland.app.core.security.KeyValueStore
import com.chunland.app.core.security.SealedStore
import com.chunland.app.core.security.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁「秘密只以密文落盘」这条策略。
 *
 * 真正的 Keystore 路径（`KeystoreSecretBox`）是 framework API，JVM 里跑不了 ——
 * 这里测的是**策略**：解不开怎么办、加不上密怎么办、缓存有没有生效。
 * Keystore 本身只能在设备上实测，别把这组用例当成「加密验过了」。
 */
class SealedStoreTest {

    /** 可控的假密盒：加密只是加个前缀，能按需让某一步失败。 */
    private class FakeBox(
        var encryptWorks: Boolean = true,
        var decryptWorks: Boolean = true,
    ) : SecretBox {
        var decryptCalls = 0
        override fun encrypt(plain: String): String? =
            if (encryptWorks) "$PREFIX$plain" else null

        override fun decrypt(stored: String): String? {
            decryptCalls++
            if (!decryptWorks || !stored.startsWith(PREFIX)) return null
            return stored.removePrefix(PREFIX)
        }

        companion object { const val PREFIX = "sealed:" }
    }

    private class MapStore(val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    @Test
    fun `写进去的是密文，读出来的是原值`() {
        val backing = MapStore()
        val store = SealedStore(FakeBox(), backing)

        store.set("token", "abc123")

        assertEquals("abc123", store.get("token"))
        assertFalse("落盘的不能是明文", backing.map["token"] == "abc123")
        assertEquals("sealed:abc123", backing.map["token"])
    }

    // ---- 解不开：加固前的明文残留，或密钥被系统作废 ----

    @Test
    fun `解不开就把那条删掉 —— 不把明文秘密继续留在磁盘上`() {
        // 加固之前留下的明文
        val backing = MapStore(mutableMapOf("token" to "legacy-plaintext-token"))
        val store = SealedStore(FakeBox(), backing)

        assertNull("解不开就当没存过", store.get("token"))
        assertFalse("明文必须被清掉，而不是留着装看不见", backing.map.containsKey("token"))
    }

    @Test
    fun `密钥被作废时同样清掉，且不抛异常`() {
        val backing = MapStore(mutableMapOf("token" to "sealed:abc"))
        val store = SealedStore(FakeBox(decryptWorks = false), backing)

        assertNull(store.get("token"))
        assertFalse(backing.map.containsKey("token"))
    }

    // ---- 加不上密：宁可不落盘，也不退回写明文 ----

    @Test
    fun `加不上密就不落盘，但当前进程仍能用`() {
        val backing = MapStore()
        val store = SealedStore(FakeBox(encryptWorks = false), backing)

        store.set("token", "abc123")

        assertTrue("绝不能退回写明文", backing.map.isEmpty())
        assertEquals("当前进程还得能跑，只是重启后就没了", "abc123", store.get("token"))
    }

    @Test
    fun `加不上密时不会留下上一次的旧密文`() {
        val box = FakeBox()
        val backing = MapStore()
        val store = SealedStore(box, backing)
        store.set("token", "old")

        box.encryptWorks = false
        store.set("token", "new")

        // 留着旧密文更糟：下次冷启动会拿一个已经作废的 token 去请求
        assertTrue(backing.map.isEmpty())
    }

    // ---- 删除与清空 ----

    @Test
    fun `set null 与空串都等于删除`() {
        val backing = MapStore()
        val store = SealedStore(FakeBox(), backing)

        store.set("a", "x"); store.set("a", null)
        store.set("b", "y"); store.set("b", "")

        assertTrue(backing.map.isEmpty())
        assertNull(store.get("a"))
        assertNull(store.get("b"))
    }

    @Test
    fun `forgetAll 之后重新从落盘读 —— 登出清缓存靠它`() {
        val backing = MapStore()
        val store = SealedStore(FakeBox(), backing)
        store.set("token", "abc")

        // 模拟 TokenStore.clear()：底层清空 + 缓存清空
        backing.map.clear()
        store.forgetAll()

        assertNull("清完还能读到旧值就说明缓存没跟着清", store.get("token"))
    }

    // ---- 主动清扫（构造时跑，不能等到被读）----

    @Test
    fun `sweep 清掉没被读过的明文残留`() {
        // refresh_token 只有 401 时才读 —— 懒清理下这条明文会一直躺在磁盘上（实测踩过）
        val backing = MapStore(mutableMapOf(
            "access_token" to "legacy-plain-access",
            "refresh_token" to "legacy-plain-refresh",
        ))
        val store = SealedStore(FakeBox(), backing)

        store.sweep(listOf("access_token", "refresh_token"))

        assertTrue("两条明文都得清掉，不能只清被读到的那条", backing.map.isEmpty())
    }

    @Test
    fun `sweep 只动点名的键 —— 同一份 prefs 里还有本来就不加密的字段`() {
        val backing = MapStore(mutableMapOf(
            "access_token" to "legacy-plain",
            "user_id" to "61",
            "active_identity" to "consumer",
        ))
        val store = SealedStore(FakeBox(), backing)

        store.sweep(listOf("access_token"))

        assertFalse(backing.map.containsKey("access_token"))
        assertEquals("非秘密字段无差别扫会被误删", "61", backing.map["user_id"])
        assertEquals("consumer", backing.map["active_identity"])
    }

    @Test
    fun `sweep 不动解得开的密文`() {
        val backing = MapStore(mutableMapOf("token" to "sealed:good"))
        val store = SealedStore(FakeBox(), backing)

        store.sweep(listOf("token"))

        assertEquals("sealed:good", backing.map["token"])
        assertEquals("good", store.get("token"))
    }

    @Test
    fun `sweepAll 扫整份 —— AI 密钥那份 prefs 每条都是秘密`() {
        val backing = MapStore(mutableMapOf(
            "apikey:a" to "legacy-plain",
            "apikey:b" to "sealed:kept",
        ))
        val store = SealedStore(FakeBox(), backing)

        store.sweepAll()

        assertFalse(backing.map.containsKey("apikey:a"))
        assertEquals("sealed:kept", backing.map["apikey:b"])
    }

    // ---- 缓存 ----

    @Test
    fun `重复读走缓存 —— 拦截器每个请求都读 token，不能每次解一遍`() {
        val box = FakeBox()
        val store = SealedStore(box, MapStore(mutableMapOf("token" to "sealed:abc")))

        repeat(5) { assertEquals("abc", store.get("token")) }

        assertEquals(1, box.decryptCalls)
    }

    @Test
    fun `读不到的 key 也缓存 —— 未登录时不该每个请求都去翻一次盘`() {
        val box = FakeBox()
        val store = SealedStore(box, MapStore())

        repeat(3) { assertNull(store.get("token")) }

        assertEquals("没存过就压根不该调 decrypt", 0, box.decryptCalls)
    }
}
