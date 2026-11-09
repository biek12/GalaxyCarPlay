package com.galaxy.autorecord.export

import com.galaxy.autorecord.core.ChannelRecord
import com.galaxy.autorecord.core.SegmentManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 跨段 PTS 重排规则（页面规格 §14.4-3）：段内相对间隔不变、段间加一个采样间隔步进、
 * 路间首帧偏移保留、export.json 可回溯。这里锁纯逻辑，播放兼容由实车/播放器验收。
 */
@RunWith(RobolectricTestRunner::class)
class ExportPlannerTest {

    private fun manifest(
        id: String,
        frontLastUs: Long,
        rearLastUs: Long = frontLastUs,
    ): SegmentManifest = SegmentManifest(
        segmentId = id,
        startedAtWallMillis = 1_000_000L,
        startedAtMonotonicNanos = 0L,
        durationMillis = frontLastUs / 1000,
        state = SegmentManifest.STATE_COMPLETE,
        storageVolumeUuid = null,
        channels = listOf(
            channel("front", first = 100_000, last = frontLastUs),
            channel("rear", first = 180_000, last = rearLastUs), // 路间首帧偏移 80ms，必须保留
            channel("left", first = 100_000, last = frontLastUs),
            channel("right", first = 100_000, last = frontLastUs),
        ),
        vehicle = null,
        events = null,
        appVersionName = "test",
    )

    private fun channel(direction: String, first: Long, last: Long) = ChannelRecord(
        direction = direction, file = "$direction.mp4", codecMime = "video/avc",
        encoderName = "test-encoder", implementation = "hardware",
        width = 1280, height = 800, fps = 25, bitrateBps = 2_000_000, gopSeconds = 1,
        frameCount = 100, firstPtsUs = first, lastPtsUs = last,
        status = SegmentManifest.CHANNEL_COMPLETE,
    )

    @Test
    fun `跨段重排为连续时间轴且段内间隔不变`() {
        val plan = ExportPlanner.plan(
            listOf(manifest("seg-1", frontLastUs = 3_960_000), manifest("seg-2", frontLastUs = 4_000_000)),
            listOf("front"),
        )
        // 段 1 从 0 起
        assertEquals(0L, plan.spans[0].offsetUs)
        assertEquals(3_960_000L, plan.spans[0].durationUs)
        // 段 2 偏移 = 段 1 末 PTS + 一个采样间隔步进
        assertEquals(3_960_000L + ExportPlanner.STEP_US, plan.spans[1].offsetUs)
        // 段内相对间隔不变：同一段内两个样本的差不变
        val a = plan.toExportPts(0, 500_000)
        val b = plan.toExportPts(0, 900_000)
        assertEquals(400_000L, b - a)
        // 跨段单调
        assertTrue(plan.toExportPts(1, 0) > plan.toExportPts(0, 3_960_000))
    }

    @Test
    fun `路间首帧偏移保留不归零`() {
        val plan = ExportPlanner.plan(
            listOf(manifest("seg-1", frontLastUs = 3_960_000, rearLastUs = 3_900_000)),
            listOf("front", "rear", "left", "right"),
        )
        // 导出时长取所选方向最晚末帧
        assertEquals(3_960_000L, plan.totalDurationUs)
        // 各路同一源 PTS 平移后仍同偏移（常量平移，路间关系不变）
        assertEquals(
            plan.toExportPts(0, 180_000) - plan.toExportPts(0, 100_000),
            80_000L,
        )
    }

    @Test
    fun `映射表可回溯到源段区间`() {
        val plan = ExportPlanner.plan(
            listOf(manifest("seg-1", 3_960_000), manifest("seg-2", 4_000_000)),
            listOf("front"),
        )
        val json = String(ExportPlanner.exportJson(plan, listOf("front"), grid = false, overlay = false))
        assertTrue(json.contains("\"segmentId\":\"seg-1\""))
        assertTrue(json.contains("\"segmentId\":\"seg-2\""))
        assertTrue(json.contains("\"exportStartMs\":0"))
        assertTrue(json.contains("\"exportStartMs\":${(3_960_000 + ExportPlanner.STEP_US) / 1000}"))
    }

    @Test
    fun `清单缺路或损坏时长如实为零且不编造`() {
        val broken = SegmentManifest(
            segmentId = "seg-broken", startedAtWallMillis = 1, startedAtMonotonicNanos = 0,
            durationMillis = 0, state = SegmentManifest.STATE_PARTIAL, storageVolumeUuid = null,
            channels = listOf(channel("front", first = 0, last = 0).copy(status = SegmentManifest.CHANNEL_MISSING)),
            vehicle = null, events = null, appVersionName = "t",
        )
        val plan = ExportPlanner.plan(listOf(broken, null), listOf("front"))
        assertEquals(0L, plan.spans[0].durationUs)
        assertEquals(0L, plan.spans[1].durationUs)
        assertEquals("segment-1", plan.spans[1].segmentId) // 无清单段如实标注占位 id
    }
}
