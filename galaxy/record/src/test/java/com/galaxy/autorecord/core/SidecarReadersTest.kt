package com.galaxy.autorecord.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 旁路数据读取（events.json / vehicles.jsonl）。
 *
 * 口径：文件不存在＝本段没有这类数据（正常）；文件存在但坏了＝必须报错，
 * 不能静默当成"0 条事件"，否则用户会以为那一段真的没发生任何事。
 */
@RunWith(RobolectricTestRunner::class)
class SidecarReadersTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun segmentDir(): File = temp.newFolder("seg")

    @Test
    fun `事件数组形式可解析`() {
        val dir = segmentDir()
        File(dir, SegmentLayout.FILE_EVENTS).writeText(
            """[{"type":"brake","startMs":1200,"endMs":2600,"peak":18.5,"ruleVersion":"v1"}]""",
        )
        val events = EventReader.read(dir)
        assertEquals(1, events.size)
        assertEquals(EventKind.BRAKE, events[0].kind)
        assertEquals(1400L, events[0].durationMs)
        assertEquals(18.5f, events[0].peak!!, 0.01f)
    }

    @Test
    fun `事件对象包裹形式可解析并保持时间排序`() {
        val dir = segmentDir()
        File(dir, SegmentLayout.FILE_EVENTS).writeText(
            """{"ruleVersion":"v1","events":[{"type":"cruise_off","startMs":900},
            {"type":"accel","startMs":100,"endMs":800}]}""",
        )
        val events = EventReader.read(dir)
        assertEquals(2, events.size)
        assertEquals(EventKind.ACCEL, events[0].kind)
        assertTrue(events[1].isDiscrete)
    }

    @Test
    fun `没有事件文件等于没有事件`() {
        assertTrue(EventReader.read(segmentDir()).isEmpty())
    }

    @Test
    fun `事件文件损坏必须报错而不是当零条`() {
        val dir = segmentDir()
        File(dir, SegmentLayout.FILE_EVENTS).writeText("{ 这不是 json")
        assertThrows(IllegalStateException::class.java) { EventReader.read(dir) }
    }

    @Test
    fun `未知事件类型被忽略而不是造一个假类型`() {
        val dir = segmentDir()
        File(dir, SegmentLayout.FILE_EVENTS).writeText("""[{"type":"teleport","startMs":0}]""")
        assertTrue(EventReader.read(dir).isEmpty())
    }

    @Test
    fun `事件摘要按时间倒序并限量`() {
        val events = (1..20).map { EventMark(EventKind.BRAKE, it * 1000L, it * 1000L + 500) }
        val summary = EventReader.summarize(events, limit = 10)
        assertEquals(10, summary.size)
        assertEquals(20_000L, summary[0].startMs)
    }

    @Test
    fun `车辆样本按嵌套与扁平两种写法都能读`() {
        val dir = segmentDir()
        File(dir, SegmentLayout.FILE_VEHICLES).writeText(
            """
            {"t":0,"v":{"displaySpeed":42,"gear":"D"}}
            {"t":500,"displaySpeed":45}
            """.trimIndent(),
        )
        val samples = VehicleHistory.read(dir)
        assertEquals(2, samples.size)
        assertEquals("42", samples[0].values["displaySpeed"])
        assertEquals("45", samples[1].values["displaySpeed"])
    }

    @Test
    fun `超出时效的样本算缺失而不是无限沿用`() {
        val dir = segmentDir()
        File(dir, SegmentLayout.FILE_VEHICLES).writeText("""{"t":1000,"v":{"displaySpeed":10}}""")
        val samples = VehicleHistory.read(dir)
        assertEquals("10", VehicleHistory.sampleAt(samples, 1500, 1000)?.values?.get("displaySpeed"))
        assertNull(VehicleHistory.sampleAt(samples, 9000, 1000))
    }

    @Test
    fun `没有车辆文件返回空而不报错`() {
        assertTrue(VehicleHistory.read(segmentDir()).isEmpty())
    }

    @Test
    fun `最小记录集与扩展项分开`() {
        assertTrue(VehicleKey.SPEED.minimalSet)
        assertTrue(VehicleKey.minimal.contains(VehicleKey.GEAR))
        assertTrue(VehicleKey.extended.contains(VehicleKey.TIRE_PRESSURE))
        assertTrue(!VehicleKey.extended.contains(VehicleKey.SPEED))
    }

    @Test
    fun `未接入车辆源不伪造数值`() {
        val feed = OfflineVehicleFeed("未接入")
        val snapshot = feed.snapshot()
        assertTrue(!snapshot.connected)
        assertEquals("—", snapshot.display(VehicleKey.SPEED))
    }
}
