package com.galaxy.auto.client.vehicle

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 账号呈现状态只通过生产解析得到，覆盖默认值、过滤、顺序和提示优先级。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class VehicleAccountStateTest {
    @Test
    fun `空对象使用原始默认值且不推断已绑定`() {
        val state = parse("{}", NOW)
        assertEquals("", state.model)
        assertEquals("", state.androidVersion)
        assertEquals("", state.serviceVersion)
        assertFalse(state.bound)
        assertFalse(state.enabled)
        assertEquals("", state.ownerName)
        assertEquals("", state.ownerPhone)
        assertEquals("", state.code)
        assertEquals("", state.scanUrl)
        assertEquals(0L, state.expiresAt)
        assertFalse(state.codeActive)
        assertFalse(state.codeVisible)
        assertTrue(state.bindings.isEmpty())
        assertEquals("", state.jsonError)
        assertTrue(state.hint(null) is VehicleAccountState.Hint.Disabled)
    }

    @Test
    fun `完整字段按原默认读取且绑定与在线彼此独立`() {
        val state =
            parse(
                vehicle(
                    bound = false,
                    enabled = true,
                    code = "0123456789abcdef",
                    expiresAt = NOW + 1,
                ),
                NOW,
            )
        assertEquals("L6", state.model)
        assertEquals("11", state.androidVersion)
        assertEquals("test", state.serviceVersion)
        assertFalse(state.bound)
        assertTrue(state.enabled)
        assertEquals("测试账号", state.ownerName)
        assertEquals("138****0000", state.ownerPhone)
        assertTrue(state.codeActive)
        assertTrue(state.codeVisible)
        assertTrue(state.hint(null) is VehicleAccountState.Hint.Enabled)
        val disabled = parse(vehicle(bound = true, enabled = false), NOW)
        assertTrue(disabled.bound)
        assertFalse(disabled.enabled)
        assertTrue(disabled.hint(null) is VehicleAccountState.Hint.Disabled)
    }

    @Test
    fun `缺字段和非法JSON不补数据`() {
        val missing = parse("""{"basicInfo":{},"owner":{},"bindings":[{}]}""", NOW)
        assertEquals("", missing.model)
        assertEquals("", missing.ownerName)
        assertTrue(missing.bindings.isEmpty())
        assertNull(VehicleAccountState.parse("{", NOW))
        assertNull(VehicleAccountState.parse("[]", NOW))
        assertNull(VehicleAccountState.parse("", NOW))
    }

    @Test
    fun `绑定码和多个请求按到期过滤并保持服务器顺序`() {
        val json =
            vehicle(
                bound = false,
                enabled = false,
                code = "code",
                expiresAt = NOW,
                bindings =
                    listOf(
                        pending("b", "乙", NOW + 5),
                        pending("a", "甲", NOW),
                        pending("c", "丙", NOW + 1),
                        pending("", "空", NOW + 5),
                    ),
            )
        val due = parse(json, NOW)
        assertFalse(due.codeActive)
        assertFalse(due.codeVisible)
        assertEquals(listOf("b", "c"), due.bindings.map { it.bindingId })
        assertEquals(listOf("乙", "丙"), due.bindings.map { it.displayName })
        val active = parse(json.replace(""""expiresAt":$NOW""", """"expiresAt":${NOW + 1}"""), NOW)
        assertTrue(active.codeVisible)
        val later = parse(json, NOW + 4)
        assertEquals(listOf("b"), later.bindings.map { it.bindingId })
        assertTrue(parse(json, NOW + 5).bindings.isEmpty())
        assertFalse(later.codeVisible)
    }

    @Test
    fun `同一JSON只改变当前时间也会移除刚过期的请求`() {
        val json = vehicle(bindings = listOf(pending("only", "账号", NOW + 1)))
        val before = parse(json, NOW)
        val after = parse(json, NOW + 1)
        assertEquals(listOf("only"), before.bindings.map { it.bindingId })
        assertTrue(after.bindings.isEmpty())
    }

    @Test
    fun `提示优先采用回执错误然后才是JSON错误和在线说明`() {
        val state = parse(vehicle(enabled = true).replace("{", """{"error":"服务端错误","""), NOW)
        assertEquals("回执", (state.hint("回执") as VehicleAccountState.Hint.Reply).text)
        assertEquals("", (state.hint("") as VehicleAccountState.Hint.Reply).text)
        assertEquals("服务端错误", (state.hint(null) as VehicleAccountState.Hint.Json).text)
        val blank = parse(vehicle(enabled = true).replace("{", """{"error":"","""), NOW)
        assertTrue(blank.hint(null) is VehicleAccountState.Hint.Enabled)
        val offline = parse(vehicle(enabled = false), NOW)
        assertTrue(offline.hint(null) is VehicleAccountState.Hint.Disabled)
    }

    private fun parse(
        json: String,
        now: Long,
    ) = requireNotNull(VehicleAccountState.parse(json, now))

    private fun vehicle(
        bound: Boolean = false,
        enabled: Boolean = false,
        code: String = "",
        expiresAt: Long = 0,
        bindings: List<String> = emptyList(),
    ): String =
        JSONObject()
            .put("bound", bound)
            .put("enabled", enabled)
            .put("code", code)
            .put("expiresAt", expiresAt)
            .put("scanUrl", "https://example.test/galaxy/app/vehicles#bind=$code")
            .put(
                "basicInfo",
                JSONObject()
                    .put("model", "L6")
                    .put("androidVersion", "11")
                    .put("serviceVersion", "test"),
            ).put(
                "owner",
                JSONObject().put("displayName", "测试账号").put("phoneMasked", "138****0000"),
            ).put(
                "bindings",
                JSONArray().apply {
                    bindings.forEach { put(JSONObject(it)) }
                },
            ).toString()

    private fun pending(
        id: String,
        name: String,
        expiresAt: Long,
    ): String =
        JSONObject()
            .put("bindingId", id)
            .put("displayName", name)
            .put("phoneMasked", "138****0000")
            .put("expiresAt", expiresAt)
            .toString()

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
