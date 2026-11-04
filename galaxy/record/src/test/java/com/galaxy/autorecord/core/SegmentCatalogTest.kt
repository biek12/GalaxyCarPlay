package com.galaxy.autorecord.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 段目录扫描与标记持久化：判定只看磁盘事实，不看内存状态。
 */
@RunWith(RobolectricTestRunner::class)
class SegmentCatalogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dayDir(): File = File(temp.root, "segments/20261103").apply { mkdirs() }

    private fun segment(name: String = "20261103-221500-000"): File =
        File(dayDir(), name).apply { mkdirs() }

    private fun touch(dir: File, name: String) {
        File(dir, name).writeText("x")
    }

    private fun manifestJson(dir: File, statuses: Map<String, String>, state: String = SegmentManifest.STATE_COMPLETE) {
        val channels = SegmentLayout.DIRECTIONS.map { direction ->
            ChannelRecord(
                direction = direction,
                file = "$direction.mp4",
                codecMime = RecordSettings.CODEC_H264,
                encoderName = "c2.test.avc.encoder",
                implementation = RecordSettings.IMPL_HARDWARE,
                width = 1920,
                height = 1088,
                fps = 25,
                bitrateBps = 4_147_200L,
                gopSeconds = 1,
                frameCount = 1500,
                firstPtsUs = 0L,
                lastPtsUs = 59_960_000L,
                status = statuses[direction] ?: SegmentManifest.CHANNEL_COMPLETE,
            )
        }
        val manifest = SegmentManifest(
            segmentId = dir.name,
            startedAtWallMillis = 1_700_000_000_000L,
            startedAtMonotonicNanos = 1_000_000L,
            durationMillis = 60_000L,
            state = state,
            storageVolumeUuid = null,
            channels = channels,
            vehicle = null,
            events = null,
            appVersionName = "test",
        )
        File(dir, SegmentLayout.FILE_MANIFEST).writeText(manifest.toJson().toString())
    }

    @Test
    fun `写入中的段不会被当成正常片段`() {
        val dir = segment()
        touch(dir, "front.mp4" + SegmentLayout.SUFFIX_WRITING)
        val result = SegmentScanner.scan(File(temp.root, "segments"))
        assertEquals(1, result.segments.size)
        assertEquals(SegmentCompleteness.WRITING, result.segments[0].completeness)
    }

    @Test
    fun `终态 partial 段带 writing 证据判为部分有效而非写入中`() {
        val dir = segment()
        touch(dir, "front.mp4")
        touch(dir, "rear.mp4" + SegmentLayout.SUFFIX_WRITING) // 失败路证据
        touch(dir, "left.mp4" + SegmentLayout.SUFFIX_WRITING)
        touch(dir, "right.mp4" + SegmentLayout.SUFFIX_WRITING)
        manifestJson(
            dir,
            mapOf(
                "front" to SegmentManifest.CHANNEL_COMPLETE,
                "rear" to SegmentManifest.CHANNEL_MISSING,
                "left" to SegmentManifest.CHANNEL_MISSING,
                "right" to SegmentManifest.CHANNEL_MISSING,
            ),
            state = SegmentManifest.STATE_PARTIAL,
        )
        val result = SegmentScanner.scan(File(temp.root, "segments"))
        assertEquals(1, result.segments.size)
        val entry = result.segments[0]
        assertEquals(SegmentCompleteness.PARTIAL, entry.completeness)
        assertEquals(setOf("rear", "left", "right"), entry.missingDirections.toSet())
        assertTrue(entry.deletableByRotation) // 不是写入中，循环覆盖可删（未锁定/无事件时）
    }

    @Test
    fun `存在 partial 文件判为部分有效`() {
        val dir = segment()
        touch(dir, "left.mp4" + SegmentLayout.SUFFIX_PARTIAL)
        val entry = SegmentScanner.scan(File(temp.root, "segments")).segments.single()
        assertEquals(SegmentCompleteness.PARTIAL, entry.completeness)
    }

    @Test
    fun `没有清单但有终名文件判为可恢复`() {
        val dir = segment()
        SegmentLayout.DIRECTIONS.forEach { touch(dir, "$it.mp4") }
        val entry = SegmentScanner.scan(File(temp.root, "segments")).segments.single()
        assertEquals(SegmentCompleteness.RECOVERABLE, entry.completeness)
        // 时长未知时必须是 0（界面显示"—"），不能猜一个默认值
        assertEquals(0L, entry.durationMillis)
    }

    @Test
    fun `清单齐全判为完整并读出时长与编码器`() {
        val dir = segment()
        SegmentLayout.DIRECTIONS.forEach { touch(dir, "$it.mp4") }
        manifestJson(dir, emptyMap())
        val entry = SegmentScanner.scan(File(temp.root, "segments")).segments.single()
        assertEquals(SegmentCompleteness.COMPLETE, entry.completeness)
        assertEquals(60_000L, entry.durationMillis)
        assertTrue(entry.encoderSummary.contains("c2.test.avc.encoder"))
        assertTrue(entry.missingDirections.isEmpty())
    }

    @Test
    fun `清单声明四路但缺文件判为文件缺失`() {
        val dir = segment()
        listOf("front", "rear", "left").forEach { touch(dir, "$it.mp4") }
        manifestJson(dir, emptyMap())
        val entry = SegmentScanner.scan(File(temp.root, "segments")).segments.single()
        assertEquals(SegmentCompleteness.MISSING_FILE, entry.completeness)
        assertTrue(entry.missingDirections.contains("right"))
    }

    @Test
    fun `空目录不产生条目`() {
        segment()
        assertTrue(SegmentScanner.scan(File(temp.root, "segments")).segments.isEmpty())
    }

    @Test
    fun `目录名可解析出开始时间`() {
        val millis = SegmentScanner.parseDirStamp("20261103-221500-000")
        assertNotNull(millis)
        // 解析不出来时不能编造当前时间，这里要求确实解出了 2026-11-03 的某个时刻
        assertTrue(millis!! > 1_700_000_000_000L)
    }

    @Test
    fun `锁定与已看标记可持久化并回读`() {
        val indexDir = File(temp.root, "index").apply { mkdirs() }
        val marks = SegmentMarks(indexDir)
        assertTrue(marks.locks().isEmpty())

        val afterLock = marks.setLocked(listOf("a", "b"), true)
        assertEquals(setOf("a", "b"), afterLock)
        assertEquals(setOf("a", "b"), SegmentMarks(indexDir).locks())

        marks.setLocked(listOf("a"), false)
        assertEquals(setOf("b"), SegmentMarks(indexDir).locks())

        assertTrue(marks.markPlayed("b"))
        assertTrue(SegmentMarks(indexDir).played().contains("b"))
    }
}
