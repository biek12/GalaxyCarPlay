package com.galaxy.autorecord.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 保留窗口与录像库筛选：这两块是"用户能不能看懂"的核心，必须有可核对的数字与口径。
 */
@RunWith(RobolectricTestRunner::class)
class RetentionAndLibraryTest {

    @Test
    fun `四路1080p25合计约16点6Mbps`() {
        val total = RetentionPlan.totalBps(1080, 25, RecordSettings.BITRATE_AUTO)
        // 0.08 × 1920 × 1080 × 25 ≈ 4.147 Mbps/路 × 4 ≈ 16.59 Mbps
        assertTrue("合计码率 ${total / 1_000_000.0} Mbps 不在预期区间", total in 16_000_000L..17_200_000L)
    }

    @Test
    fun `按实测1280x800原生分辨率默认配额约九小时`() {
        val quota = RecordSettings.AUTO_QUOTA_BYTES
        val millis = RetentionPlan.backtraceMillis(quota, 0, 25, RecordSettings.BITRATE_AUTO)
        val hours = millis / 3_600_000.0
        // 实测相机只有 1280×800（不是 1080p），四路约 8.19 Mbps，32 GiB 约 9.3 小时
        assertTrue("回溯 $hours 小时不在 8.5–10.0 之间", hours in 8.5..10.0)
        assertTrue("9 小时不该触发 <2h 警示", !RetentionPlan.isShortWindow(millis))
    }

    @Test
    fun `小配额触发两小时警示线`() {
        val small = RetentionPlan.backtraceMillis(4L * 1024 * 1024 * 1024, 0, 25, RecordSettings.BITRATE_AUTO)
        assertTrue("4 GB 配额应触发 <2h 警示", RetentionPlan.isShortWindow(small))
        val medium = RetentionPlan.backtraceMillis(8L * 1024 * 1024 * 1024, 0, 25, RecordSettings.BITRATE_AUTO)
        assertTrue("8 GB 约 2.3 小时，不应触发 <2h 警示", !RetentionPlan.isShortWindow(medium))
    }

    @Test
    fun `省空间档明显拉长回溯窗口`() {
        val quota = RecordSettings.AUTO_QUOTA_BYTES
        val hd = RetentionPlan.backtraceMillis(quota, 1080, 25, RecordSettings.BITRATE_AUTO)
        val sd = RetentionPlan.backtraceMillis(quota, 720, 25, RecordSettings.BITRATE_AUTO)
        assertTrue("720p 应至少比 1080p 长 2 倍", sd > hd * 2)
    }

    @Test
    fun `跟随原生取实测JPEG尺寸而不是猜1080p`() {
        val (width, height) = RetentionPlan.resolution(0)
        assertEquals(CameraProfile.JPEG_WIDTH, width)
        assertEquals(CameraProfile.JPEG_HEIGHT, height)
        assertEquals(720, RetentionPlan.resolution(720).second)
    }

    @Test
    fun `1080p与30fps在实测能力之外必须带原因拒绝`() {
        assertTrue(RetentionPlan.isSupportedHeight(0))
        assertTrue(RetentionPlan.isSupportedHeight(720))
        assertTrue("原生只有 800 高，1080p 属于上采样，必须不可用", !RetentionPlan.isSupportedHeight(1080))
        assertTrue(RetentionPlan.isSupportedFps(25))
        assertTrue("实测上限 25fps，30fps 不可用", !RetentionPlan.isSupportedFps(30))
        assertTrue(RetentionPlan.unsupportedReason(1080, 25)!!.contains("上采样"))
        assertTrue(RetentionPlan.unsupportedReason(0, 30)!!.contains("上限"))
        assertNull(RetentionPlan.unsupportedReason(0, 25))
    }

    @Test
    fun `回溯文案在未知时给破折号而不给0小时`() {
        assertEquals("—", RetentionPlan.durationLabel(0))
        assertEquals("4 小时", RetentionPlan.durationLabel(4 * 3_600_000L))
        assertTrue(RetentionPlan.backtraceLabel(0, 1080, 25, RecordSettings.BITRATE_AUTO).contains("—"))
    }

    private fun entry(
        id: String,
        startMillis: Long,
        completeness: SegmentCompleteness = SegmentCompleteness.COMPLETE,
        locked: Boolean = false,
        seen: Boolean = false,
        events: Int = 0,
        missing: List<String> = emptyList(),
    ) = SegmentEntry(
        id = id,
        dir = java.io.File("/tmp/$id"),
        startedAtMillis = startMillis,
        durationMillis = 60_000L,
        bytes = 1024L,
        completeness = completeness,
        missingDirections = missing,
        eventCount = events,
        locked = locked,
        seen = seen,
    )

    @Test
    fun `异常筛选只包含非完整片段且排除写入中`() {
        val now = 1_700_000_000_000L
        val state = LibraryState(
            segments = listOf(
                entry("a", now),
                entry("b", now - 1000, completeness = SegmentCompleteness.PARTIAL),
                entry("c", now - 2000, completeness = SegmentCompleteness.WRITING),
            ),
            scanned = true,
        )
        assertEquals(listOf("b"), state.query(LibraryFilter.ABNORMAL).map { it.id })
        assertEquals(listOf("a", "b"), state.query(LibraryFilter.ALL).map { it.id })
    }

    @Test
    fun `重要等于已锁定或含事件`() {
        val now = 1_700_000_000_000L
        val state = LibraryState(
            segments = listOf(
                entry("a", now),
                entry("b", now - 1000, locked = true),
                entry("c", now - 2000, events = 3),
            ),
            scanned = true,
        )
        assertEquals(listOf("b", "c"), state.query(LibraryFilter.IMPORTANT).map { it.id })
    }

    @Test
    fun `未看不含异常片段`() {
        val now = 1_700_000_000_000L
        val state = LibraryState(
            segments = listOf(
                entry("a", now),
                entry("b", now - 1000, seen = true),
                entry("c", now - 2000, completeness = SegmentCompleteness.MISSING_FILE),
            ),
            scanned = true,
        )
        assertEquals(listOf("a"), state.query(LibraryFilter.UNSEEN).map { it.id })
    }

    @Test
    fun `角标按优先级排序且异常优先于锁定`() {
        val e = entry("a", 0, completeness = SegmentCompleteness.PARTIAL, locked = true, events = 2, missing = listOf("left"))
        val badges = e.badges()
        assertEquals("部分有效", badges[0])
        assertTrue(badges.indexOf("缺路 左") > 0)
        assertTrue(badges.indexOf("已锁定") > badges.indexOf("缺路 左"))
        assertTrue(badges.indexOf("含事件 2") > badges.indexOf("已锁定"))
    }

    @Test
    fun `受保护片段不参与循环覆盖`() {
        assertTrue(!entry("a", 0, locked = true).deletableByRotation)
        assertTrue(!entry("b", 0, events = 1).deletableByRotation)
        assertTrue(entry("c", 0).deletableByRotation)
    }

    @Test
    fun `跨段邻居只在间隔不超过一个分段时长时成立`() {
        val now = 1_700_000_000_000L
        val state = LibraryState(
            segments = listOf(
                entry("a", now),
                entry("b", now + 60_000),
                entry("c", now + 60_000 + 5 * 60_000),
            ),
            scanned = true,
        )
        val (prev, next) = state.neighbors(state.segments[1], segmentSeconds = 60)
        assertEquals("a", prev?.id)
        assertNull("间隔 5 分钟不算同会话相邻段", next)
    }

    @Test
    fun `未扫描与空库必须可区分`() {
        assertTrue(!LibraryState().scanned)
        assertTrue(!LibraryState().isEmptyButKnown)
        assertTrue(LibraryState(scanned = true).isEmptyButKnown)
        assertTrue(!LibraryState(scanned = true, error = "读取失败").isEmptyButKnown)
    }

    @Test
    fun `按日期分组并给出今天标签`() {
        val now = System.currentTimeMillis()
        val state = LibraryState(segments = listOf(entry("a", now)), scanned = true)
        val groups = state.groupByDay(state.segments, now)
        assertEquals(1, groups.size)
        assertEquals("今天", groups[0].label)
        assertNotNull(groups[0].segments.singleOrNull())
    }
}
