package com.chunland.app

import com.chunland.app.core.ai.domain.AgentParamType
import com.chunland.app.core.ai.domain.AgentToolDefinition
import com.chunland.app.core.ai.tools.AgentRemoteToolSpec
import com.chunland.app.core.ai.tools.AiWireToolCatalog
import com.chunland.app.core.ai.tools.mergeWireTools
import com.chunland.app.core.ai.tools.toSpec
import com.chunland.app.core.security.KeyValueStore
import com.chunland.app.data.api.AiToolSchemaResponse
import com.chunland.app.data.api.AiWireParamDto
import com.chunland.app.data.api.AiWireToolDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁「只读工具 schema 线上下发」这套机制。
 *
 * 这里最重要的一条不是功能，是**安全不变量**：
 * 下发的工具在端上一律只读。变更工具的 `kind: MUTATION` 决定弹不弹 HITL 确认框，
 * 那是模型「想下单」与「真下单」之间唯一的人工闸门 —— 它绝不能由下发内容决定。
 * `AgentRemoteToolSpec` 结构上就没有 kind 字段，所以这条由构造保证；
 * 下面的用例守的是「接线没接错」。
 */
class AiWireToolsTest {

    private fun dto(
        name: String,
        identities: List<String> = listOf("consumer"),
        params: List<AiWireParamDto> = emptyList(),
    ) = AiWireToolDto(name, "描述 $name", identities, params)

    private fun param(
        name: String,
        type: String = "string",
        required: Boolean = false,
        enumValues: List<String>? = null,
        itemType: String? = null,
    ) = AiWireParamDto(name, type, "说明 $name", required, enumValues, itemType)

    private class MapStore(val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    // ---- DTO → spec 映射 ----

    @Test
    fun `参数名保持 snake_case`() {
        // 参数名一旦当成 JSON 的 key 就会被键归一化转成 priceMin ——
        // 与钉死工具的口径对不上，且不报错。所以 wire 用数组带 name。
        val spec = dto("t", params = listOf(param("price_min"), param("in_stock", "boolean"))).toSpec()

        assertEquals(setOf("price_min", "in_stock"), spec.definition.parameters.keys)
        assertEquals(AgentParamType.BOOLEAN, spec.definition.parameters["in_stock"]?.type)
    }

    @Test
    fun `required 枚举与顺序都带过来`() {
        val spec = dto("t", params = listOf(
            param("a", required = true),
            param("sort", enumValues = listOf("asc", "desc")),
            param("tags", type = "array", itemType = "string"),
        )).toSpec()

        assertEquals(listOf("a"), spec.definition.required)
        assertEquals(listOf("asc", "desc"), spec.definition.parameters["sort"]?.enumValues)
        assertEquals(AgentParamType.STRING, spec.definition.parameters["tags"]?.itemType)
        // 数组顺序即生成顺序 —— 部分模型对参数顺序敏感
        assertEquals(listOf("a", "sort", "tags"), spec.definition.propertyOrdering)
    }

    @Test
    fun `未知参数类型退化成 string 而不是丢掉整个工具`() {
        val spec = dto("t", params = listOf(param("x", type = "no_such_type"))).toSpec()

        assertEquals(AgentParamType.STRING, spec.definition.parameters["x"]?.type)
        assertEquals("t", spec.name)
    }

    // ---- 合并规则（安全不变量）----

    private fun definition(name: String) =
        AgentToolDefinition(name, "钉死的 $name", emptyMap(), emptyList())

    @Test
    fun `重名时钉死的赢 —— 下发不能遮蔽真的 place_order`() {
        var collided: String? = null
        val merged = mergeWireTools(
            pinned = listOf(definition("place_order")),
            pinnedNames = setOf("place_order"),
            wire = listOf(dto("place_order").toSpec()),
            identity = "consumer",
            onCollision = { collided = it },
        )

        assertEquals(1, merged.size)
        assertEquals("钉死的 place_order", merged[0].description)
        assertEquals("place_order", collided)
    }

    @Test
    fun `按身份裁剪`() {
        val wire = listOf(dto("list_stores", identities = listOf("consumer")).toSpec())

        assertEquals(listOf("list_stores"),
            mergeWireTools(emptyList(), emptySet(), wire, "consumer").map { it.name })
        assertEquals(emptyList<String>(),
            mergeWireTools(emptyList(), emptySet(), wire, "agent").map { it.name })
    }

    @Test
    fun `下发工具追加在钉死的之后，不打乱既有顺序`() {
        val merged = mergeWireTools(
            pinned = listOf(definition("search_products"), definition("get_cart")),
            pinnedNames = setOf("search_products", "get_cart"),
            wire = listOf(dto("list_stores").toSpec()),
            identity = "consumer",
        )
        assertEquals(listOf("search_products", "get_cart", "list_stores"), merged.map { it.name })
    }

    // ---- 缓存与失败降级 ----

    private fun catalog(store: KeyValueStore, fetcher: suspend (String) -> AiToolSchemaResponse) =
        AiWireToolCatalog(store, CoroutineScope(Dispatchers.Unconfined), fetcher)

    @Test
    fun `拉到就缓存，重复取不再打网络`() = runBlocking {
        var calls = 0
        val c = catalog(MapStore()) { calls++; AiToolSchemaResponse(1, listOf(dto("list_stores"))) }

        repeat(3) { assertEquals(listOf("list_stores"), c.tools("consumer").map { it.name }) }
        assertEquals(1, calls)
    }

    @Test
    fun `拉取失败回落落盘缓存 —— 离线冷启动仍有工具`() = runBlocking {
        val store = MapStore()
        catalog(store) { AiToolSchemaResponse(1, listOf(dto("list_stores"))) }.tools("consumer")
        assertTrue("第一次应当落盘", store.map.isNotEmpty())

        // 换一个必然失败的 fetcher，模拟离线冷启动
        val offline = catalog(store) { throw RuntimeException("网络不可用") }
        assertEquals(listOf("list_stores"), offline.tools("consumer").map { it.name })
    }

    @Test
    fun `没有缓存时拉取失败给空集，绝不抛`() = runBlocking {
        val c = catalog(MapStore()) { throw RuntimeException("网络不可用") }
        // 「永远不阻塞对话」：AI 照常能聊，只是少几个工具
        assertEquals(emptyList<AgentRemoteToolSpec>(), c.tools("consumer"))
    }

    @Test
    fun `失败后不记时间戳 —— 下次还会再试`() = runBlocking {
        var calls = 0
        val c = catalog(MapStore()) { calls++; throw RuntimeException("网络不可用") }

        c.tools("consumer")
        c.tools("consumer")
        assertEquals("失败不该被当成「已拉过」缓存住", 2, calls)
    }

    @Test
    fun `reset 清缓存与落盘 —— 切账号后身份集可能不同`() = runBlocking {
        val store = MapStore()
        val c = catalog(store) { AiToolSchemaResponse(1, listOf(dto("list_stores"))) }
        c.tools("consumer")

        c.reset()
        assertTrue(store.map.isEmpty())

        val offline = catalog(store) { throw RuntimeException("网络不可用") }
        assertFalse("清完还能从盘上读回来就说明没清干净",
            offline.tools("consumer").isNotEmpty())
    }
}
