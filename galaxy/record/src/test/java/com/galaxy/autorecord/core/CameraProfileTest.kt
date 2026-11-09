package com.galaxy.autorecord.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 实测相机基线与方位标定。
 *
 * 这一组测试的价值在于把"实测事实"钉住：1280×800、25fps 上限、6 MB 缓冲按 EOI 截断、
 * cameraId 只认 0–3、51 是别名。任何一条被改回"常见机型默认值"都应该让测试先红。
 */
@RunWith(RobolectricTestRunner::class)
class CameraProfileTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** 拼一个结构合法的最小 JPEG：SOI + APP0 + SOS + 熵数据 + EOI。 */
    private fun minimalJpeg(entropy: ByteArray = bytes(0x00, 0x01, 0x02)): ByteArray {
        val head = bytes(
            0xFF, 0xD8,                          // SOI
            0xFF, 0xE0, 0x00, 0x04, 0x00, 0x00,  // APP0, len=4
            0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x02, 0x01, 0x00, 0x00, // SOS, len=8
        )
        return head + entropy + bytes(0xFF, 0xD9)
    }

    @Test
    fun `按EOI截断并丢掉尾部垃圾`() {
        val jpeg = minimalJpeg()
        // 模拟相机返回的 6 MB 缓冲：真实数据 + 大量 0 填充
        val buffer = jpeg + ByteArray(CameraProfile.BUFFER_BYTES - jpeg.size)
        val end = CameraProfile.jpegEndOffset(buffer)
        assertEquals(jpeg.size, end)
        assertTrue("截断后必须远小于 6 MB 缓冲", end < CameraProfile.BUFFER_BYTES)
    }

    @Test
    fun `没有EOI的坏帧返回负值而不是按缓冲长度落盘`() {
        val truncated = minimalJpeg().copyOf(minimalJpeg().size - 2)
        assertEquals(-1, CameraProfile.jpegEndOffset(truncated))
        assertFalse(CameraProfile.isCompleteJpeg(truncated, truncated.size))
    }

    @Test
    fun `EXIF内嵌缩略图的假EOI不会导致提前截断`() {
        // APP1 段内部含一个完整的小 JPEG（自带 FFD9），整体结构仍然是段 + SOS + 熵数据 + 真 EOI
        val innerJpeg = minimalJpeg(bytes(0x11, 0x22))
        val app1Payload = innerJpeg + bytes(0x00, 0xAA)
        val app1Length = app1Payload.size + 2
        val head = bytes(0xFF, 0xD8, 0xFF, 0xE1, (app1Length shr 8) and 0xFF, app1Length and 0xFF) + app1Payload +
            bytes(0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x02, 0x01, 0x00, 0x00) +
            bytes(0x33, 0x44)
        val realJpeg = head + bytes(0xFF, 0xD9)
        val buffer = realJpeg + ByteArray(1024)

        val end = CameraProfile.jpegEndOffset(buffer)
        assertEquals("必须停在真 EOI，而不是缩略图的 EOI", realJpeg.size, end)
        assertTrue(CameraProfile.isCompleteJpeg(buffer, end))
    }

    @Test
    fun `熵数据里的字节填充FF00不被误判为标记`() {
        val jpeg = minimalJpeg(bytes(0xFF, 0x00, 0x7F, 0xFF, 0x00, 0x01))
        assertEquals(jpeg.size, CameraProfile.jpegEndOffset(jpeg + ByteArray(64)))
    }

    @Test
    fun `不是JPEG开头的缓冲直接判坏`() {
        assertEquals(-1, CameraProfile.jpegEndOffset(ByteArray(16)))
    }

    @Test
    fun `实测能力基线不许被改回通用默认值`() {
        assertEquals(listOf(0, 1, 2, 3), CameraProfile.USABLE_IDS)
        assertEquals(2, CameraProfile.ALIAS_IDS[51])
        assertTrue(CameraProfile.FAILING_IDS.contains(4))
        assertTrue(CameraProfile.FAILING_IDS.contains(50))
        assertEquals(1280, CameraProfile.JPEG_WIDTH)
        assertEquals(800, CameraProfile.JPEG_HEIGHT)
        assertEquals(25, CameraProfile.MAX_FPS)
        assertEquals(6_000_000, CameraProfile.BUFFER_BYTES)
        assertFalse("facing 恒 FRONT，不能用来判方位", CameraProfile.DIRECTION_FROM_CHARACTERISTICS_RELIABLE)
        assertFalse("本轮未完成地面真值标定", CameraProfile.DIRECTION_CALIBRATED)
    }

    @Test
    fun `未验证项必须显式列出`() {
        val items = CameraProfile.unverifiedItems()
        assertTrue(items.any { it.contains("长时间") || it.contains("推流") })
        assertTrue(items.any { it.contains("标定") })
    }

    @Test
    fun `默认标定为假设A且标记未标定`() {
        val layout = CameraLayout.DEFAULT
        assertEquals(0, layout.cameraIdOf("left"))
        assertEquals(1, layout.cameraIdOf("right"))
        assertEquals(2, layout.cameraIdOf("front"))
        assertEquals(3, layout.cameraIdOf("rear"))
        assertFalse(layout.calibrated)
        assertTrue(layout.isValid)
        assertTrue(layout.summary().contains("未标定"))
    }

    @Test
    fun `重复映射与不可用ID都判为不合法`() {
        val duplicated = CameraLayout(
            mapOf("left" to 0, "right" to 0, "front" to 2, "rear" to 3),
            calibrated = true,
        )
        assertFalse(duplicated.isValid)
        assertEquals(listOf(0), duplicated.duplicates())

        val badId = CameraLayout(
            mapOf("left" to 0, "right" to 1, "front" to 2, "rear" to 51),
            calibrated = true,
        )
        assertFalse("51 是别名，不能当独立一路", badId.isValid)
        assertTrue(badId.badDirections().contains("rear"))
    }

    @Test
    fun `缺一个方向也判为不合法`() {
        val partial = CameraLayout(mapOf("left" to 0, "right" to 1, "front" to 2), calibrated = true)
        assertFalse(partial.isValid)
        assertTrue(partial.badDirections().contains("rear"))
    }

    @Test
    fun `假设B与假设A在前后上的差异被保留`() {
        assertEquals(2, CameraLayout.ASSUMED_A.cameraIdOf("front"))
        assertEquals(3, CameraLayout.ASSUMED_B.cameraIdOf("front"))
        assertEquals("left", CameraLayout.ASSUMED_B.directionOf(0))
        assertEquals("front", CameraLayout.ASSUMED_B.directionOf(3))
    }

    @Test
    fun `全占用时可整体交换前后且保留左右与未标定状态`() {
        val changed = CameraLayout.DEFAULT.withCameraId("front", 3)!!
        assertTrue(changed.isValid)
        assertEquals(CameraLayout.ASSUMED_B.mapping, changed.mapping)
        assertFalse(changed.calibrated)
        assertTrue(changed.summary().contains("待核对"))
        assertEquals(CameraLayout.DEFAULT.mapping, changed.withCameraId("front", 2)!!.mapping)
        assertEquals(2, CameraLayout.DEFAULT.cameraIdOf("front"))
    }

    @Test
    fun `交换拒绝别名与未知方向不损坏现有映射`() {
        assertEquals(null, CameraLayout.DEFAULT.withCameraId("front", 51))
        assertEquals(null, CameraLayout.DEFAULT.withCameraId("roof", 0))
        assertTrue(CameraLayout.DEFAULT.isValid)
    }
}
