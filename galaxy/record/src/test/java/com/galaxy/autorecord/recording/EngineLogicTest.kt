package com.galaxy.autorecord.recording

import com.galaxy.autorecord.core.EventKind
import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.SegmentMarks
import com.galaxy.autorecord.core.SegmentManifest
import com.galaxy.autorecord.core.ChannelRecord
import com.galaxy.autorecord.core.AtomicFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * 引擎侧纯逻辑单测：事件判定（阈值/去抖/合并/最小事件时长）与循环覆盖（保护段不可删）。
 * 真实编码/写盘链路由实车与 Instrumentation 验收，这里只锁产品规则。
 */
@RunWith(RobolectricTestRunner::class)
class EventDetectorTest {

    private fun rules(
        debounce: Int = 200,
        minEvent: Int = 300,
        merge: Int = 1000,
    ) = EventDetector.Rules(
        brakeEnter = 5, brakeExit = 3, accelEnter = 20, accelExit = 10,
        debounceMs = debounce, minEventMs = minEvent, mergeMs = merge,
        ruleVersion = "test-rules",
    )

    @Test
    fun `刹车越过阈值持续超过去抖才产生事件并回溯到起始时刻`() {
        val detector = EventDetector(rules())
        detector.onSample(0, brakePct = 0f, accelPct = null, adasActive = null, cruiseActive = null)
        detector.onSample(100, brakePct = 10f, accelPct = null, adasActive = null, cruiseActive = null) // 进入候选
        detector.onSample(250, brakePct = 10f, accelPct = null, adasActive = null, cruiseActive = null) // 去抖不足 200ms? 150ms
        detector.onSample(350, brakePct = 10f, accelPct = null, adasActive = null, cruiseActive = null) // 候选持续 250ms ≥ 200 → 确认
        detector.onSample(700, brakePct = 1f, accelPct = null, adasActive = null, cruiseActive = null) // 退出候选
        detector.onSample(950, brakePct = 1f, accelPct = null, adasActive = null, cruiseActive = null) // 退出确认
        val events = detector.finish(1000)
        val brake = events.single { it.kind == EventKind.BRAKE }
        assertEquals(100L, brake.startMs) // 回溯到首次越过阈值
        assertEquals(700L, brake.endMs)   // 回溯到首次回落
        assertEquals(10f, brake.peak!!)
        assertEquals("test-rules", brake.ruleVersion)
    }

    @Test
    fun `短于最小事件时长的刹车不产生事件`() {
        val detector = EventDetector(rules(minEvent = 300))
        detector.onSample(0, brakePct = 0f, accelPct = null, adasActive = null, cruiseActive = null)
        detector.onSample(50, brakePct = 20f, accelPct = null, adasActive = null, cruiseActive = null)
        detector.onSample(300, brakePct = 20f, accelPct = null, adasActive = null, cruiseActive = null) // 确认（start=50）
        detector.onSample(320, brakePct = 0f, accelPct = null, adasActive = null, cruiseActive = null) // 退出候选
        detector.onSample(530, brakePct = 0f, accelPct = null, adasActive = null, cruiseActive = null) // 确认（end=320）
        val events = detector.finish(600)
        // 时长 270ms < 300ms：不产生事件
        assertTrue(events.none { it.kind == EventKind.BRAKE })
    }

    @Test
    fun `邻近两段刹车在合并间隔内合并为一条`() {
        val detector = EventDetector(rules(merge = 1000))
        // 第一段：0..500
        detector.onSample(0, brakePct = 20f, accelPct = null, adasActive = null, cruiseActive = null)
        detector.onSample(250, brakePct = 20f, accelPct = null, adasActive = null, cruiseActive = null)
        detector.onSample(300, brakePct = 0f, accelPct = null, adasActive = null, cruiseActive = null)
        detector.onSample(550, brakePct = 0f, accelPct = null, adasActive = null, cruiseActive = null) // end=300
        // 第二段：600..1200（距上一段 end 300ms ≤ 1000ms）
        detector.onSample(600, brakePct = 30f, accelPct = null, adasActive = null, cruiseActive = null)
        detector.onSample(850, brakePct = 30f, accelPct = null, adasActive = null, cruiseActive = null)
        detector.onSample(950, brakePct = 0f, accelPct = null, adasActive = null, cruiseActive = null)
        detector.onSample(1200, brakePct = 0f, accelPct = null, adasActive = null, cruiseActive = null)
        val events = detector.finish(1300)
        val brake = events.single { it.kind == EventKind.BRAKE }
        assertEquals(0L, brake.startMs)
        assertEquals(950L, brake.endMs)
        assertEquals(30f, brake.peak!!) // 合并后取峰值
    }

    @Test
    fun `横向辅助启停产生离散事件且不臆造退出`() {
        val detector = EventDetector(rules())
        detector.onSample(0, brakePct = null, accelPct = null, adasActive = false, cruiseActive = null)
        detector.onSample(100, brakePct = null, accelPct = null, adasActive = true, cruiseActive = null) // 候选
        detector.onSample(400, brakePct = null, accelPct = null, adasActive = true, cruiseActive = null) // 确认 @100
        detector.onSample(1000, brakePct = null, accelPct = null, adasActive = false, cruiseActive = null) // 候选
        detector.onSample(1300, brakePct = null, accelPct = null, adasActive = false, cruiseActive = null) // 确认 @1000
        val events = detector.finish(1400)
        assertEquals(listOf(EventKind.ADAS_ON, EventKind.ADAS_OFF), events.map { it.kind })
        assertEquals(100L, events[0].startMs)
        assertEquals(1000L, events[1].startMs)
        assertTrue(events.all { it.isDiscrete })
    }

    @Test
    fun `缺失样本不触发事件也不补零`() {
        val detector = EventDetector(rules())
        detector.onSample(0, brakePct = null, accelPct = null, adasActive = null, cruiseActive = null)
        detector.onSample(500, brakePct = 50f, accelPct = null, adasActive = null, cruiseActive = null) // 候选
        detector.onSample(550, brakePct = null, accelPct = null, adasActive = null, cruiseActive = null) // 缺失：状态不变
        detector.onSample(800, brakePct = 50f, accelPct = null, adasActive = null, cruiseActive = null) // 确认 @500
        val events = detector.finish(900) // 持续到收尾仍激活 → 关闭
        val brake = events.single { it.kind == EventKind.BRAKE }
        assertEquals(500L, brake.startMs)
        assertEquals(900L, brake.endMs)
    }

    @Test
    fun `数值解析不臆造`() {
        assertEquals(42f, EventDetector.parsePercent("42"))
        assertEquals(42.5f, EventDetector.parsePercent(" 42.5% "))
        assertNull(EventDetector.parsePercent("—"))
        assertNull(EventDetector.parsePercent(null))
        assertEquals(true, EventDetector.parseActive("on"))
        assertEquals(false, EventDetector.parseActive("OFF"))
        assertNull(EventDetector.parseActive("maybe"))
    }
}

@RunWith(RobolectricTestRunner::class)
class RotationJanitorTest {

    private fun writeSegment(base: File, id: String, startedAt: Long, bytes: Long, events: Int = 0, locked: Boolean = false): File {
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(startedAt))
        val dir = File(File(SegmentLayout.segmentsRoot(base), day), id)
        dir.mkdirs()
        val channels = SegmentLayout.DIRECTIONS.map { d ->
            ChannelRecord(
                direction = d, file = "$d.mp4", codecMime = "video/avc", encoderName = "e",
                implementation = "hardware", width = 1280, height = 800, fps = 25,
                bitrateBps = 2_000_000, gopSeconds = 1, frameCount = 100,
                firstPtsUs = 0, lastPtsUs = 3_960_000, status = SegmentManifest.CHANNEL_COMPLETE,
            )
        }
        val manifest = SegmentManifest(
            segmentId = id, startedAtWallMillis = startedAt, startedAtMonotonicNanos = 0,
            durationMillis = 3960, state = SegmentManifest.STATE_COMPLETE, storageVolumeUuid = null,
            channels = channels, vehicle = null,
            events = if (events > 0) com.galaxy.autorecord.core.EventsRecord("r", events, SegmentLayout.FILE_EVENTS) else null,
            appVersionName = "test",
        )
        AtomicFiles.writeAtomically(File(dir, SegmentLayout.FILE_MANIFEST), manifest.toJson().toString().toByteArray())
        if (events > 0) {
            AtomicFiles.writeAtomically(File(dir, SegmentLayout.FILE_EVENTS), """{"ruleVersion":"r","events":[]}""".toByteArray())
        }
        // 占字节
        val pad = File(dir, "front.mp4")
        pad.writeBytes(ByteArray(bytes.coerceAtMost(4096).toInt()))
        if (locked) {
            SegmentMarks(SegmentLayout.indexDir(base)).setLocked(listOf(id), true)
        }
        return dir
    }

    @Test
    fun `缓存目录仍按实际写入账本腾空间且重新读取锁定标记`() {
        val base=Files.createTempDirectory("janitor-ledger").toFile()
        val old=writeSegment(base,"20261104-100000-001",1_000_000L,100)
        val protected=writeSegment(base,"20261104-100100-002",2_000_000L,100)
        val first=RotationJanitor.makeRoom(SegmentLayout.segmentsRoot(base),SegmentLayout.indexDir(base),
            1_000_000,1_000_000,0,1,true,true)
        assertEquals(first.catalog.sumOf{it.bytes},first.usedBytes)
        SegmentMarks(SegmentLayout.indexDir(base)).setLocked(listOf(protected.name),true)
        val result=RotationJanitor.makeRoom(SegmentLayout.segmentsRoot(base),SegmentLayout.indexDir(base),
            10_000,1_000_000,0,1,true,true,catalog=first.catalog,usedBytesOverride=10_000)
        assertEquals(listOf(old.name),result.deleted)
        assertTrue(protected.exists())
        assertEquals(10_000-result.freedBytes,result.usedBytes)
        assertEquals(listOf(protected.name),result.catalog.map{it.id})
        base.deleteRecursively()
    }

    @Test
    fun `空间充足时不删除任何段`() {
        val base = Files.createTempDirectory("janitor").toFile()
        writeSegment(base, "20261104-100000-001", 1_000_000L, 100)
        val result = RotationJanitor.makeRoom(
            SegmentLayout.segmentsRoot(base), SegmentLayout.indexDir(base),
            quotaBytes = 10_000_000, freeBytes = 10_000_000, minFreeBytes = 0,
            incomingBytes = 100, rotate = true, eventProtect = true,
        )
        assertTrue(result.deleted.isEmpty())
        assertNull(result.blockedReason)
        base.deleteRecursively()
    }

    @Test
    fun `配额不足时删最旧且未锁定未含事件的段`() {
        val base = Files.createTempDirectory("janitor").toFile()
        val s1 = writeSegment(base, "20261104-100000-001", 1_000_000L, 100)
        val s2 = writeSegment(base, "20261104-100100-002", 2_000_000L, 100)
        val s3 = writeSegment(base, "20261104-100200-003", 3_000_000L, 100, events = 1) // 事件保护
        val s4 = writeSegment(base, "20261104-100300-004", 4_000_000L, 100, locked = true) // 锁定
        // 配额取"002+003+004 实际字节 − 1"：删掉 001 后仍差一点，须再删 002 才够
        val quota = s2.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
            s3.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
            s4.walkTopDown().filter { it.isFile }.sumOf { it.length() } - 1
        val result = RotationJanitor.makeRoom(
            SegmentLayout.segmentsRoot(base), SegmentLayout.indexDir(base),
            quotaBytes = quota, freeBytes = 10_000_000, minFreeBytes = 0,
            incomingBytes = 1, rotate = true, eventProtect = true,
        )
        assertEquals(listOf("20261104-100000-001", "20261104-100100-002"), result.deleted)
        assertFalse(s1.exists())
        assertFalse(s2.exists())
        assertTrue(s3.exists()) // 事件保护：不删
        assertTrue(s4.exists()) // 锁定：不删
        base.deleteRecursively()
    }

    @Test
    fun `受保护段占满配额时明确报原因且不删保护段`() {
        val base = Files.createTempDirectory("janitor").toFile()
        val s3 = writeSegment(base, "20261104-100200-003", 3_000_000L, 100, events = 1)
        val s4 = writeSegment(base, "20261104-100300-004", 4_000_000L, 100, locked = true)
        val result = RotationJanitor.makeRoom(
            SegmentLayout.segmentsRoot(base), SegmentLayout.indexDir(base),
            quotaBytes = 1, freeBytes = 10_000_000, minFreeBytes = 0,
            incomingBytes = 100, rotate = true, eventProtect = true,
        )
        assertTrue(result.deleted.isEmpty())
        assertTrue(result.blockedReason!!.contains("受保护片段"))
        assertTrue(result.blockedReason!!.contains("锁定 1 段"))
        assertTrue(result.blockedReason!!.contains("事件 1 段"))
        assertTrue(s3.exists())
        assertTrue(s4.exists())
        base.deleteRecursively()
    }

    @Test
    fun `覆盖关闭时空间不足直接报错不删段`() {
        val base = Files.createTempDirectory("janitor").toFile()
        val s1 = writeSegment(base, "20261104-100000-001", 1_000_000L, 100)
        val result = RotationJanitor.makeRoom(
            SegmentLayout.segmentsRoot(base), SegmentLayout.indexDir(base),
            quotaBytes = 1, freeBytes = 10_000_000, minFreeBytes = 0,
            incomingBytes = 100, rotate = false, eventProtect = true,
        )
        assertTrue(result.deleted.isEmpty())
        assertTrue(result.blockedReason!!.contains("循环覆盖已关闭"))
        assertTrue(s1.exists())
        base.deleteRecursively()
    }
}
