package com.galaxy.autorecord.storage

import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.SegmentManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * SegmentTransaction 的失败路径与幂等测试（WB-STORAGE-01 / R1 审查反例）。
 *
 * 纪律：空视频、非法/相等 PTS、单路失败、清单失败、旧段/他事务结果混入都不得伪造成 complete；
 * finish/abort/close 幂等；单一写线程所有权；端口异常转具名失败且不抛给上层；清单始终固定四向。
 *
 * 本文件由 Lead 经 `bash scripts/verify_workbuddy_storage.sh` 在 Android 测试环境统一运行；
 * WorkBuddy 侧不执行（按协作分工不跑 Gradle/模拟器/安装），故下方如实标注"未运行"。
 * 部分用例直接针对 [RealSegmentStorePort] 使用真实临时目录验证原子独占创建（R1-2）。
 */
class SegmentTransactionTest {

    private val rootDir = File(System.getProperty("java.io.tmpdir")!!, "wb-storage-test-root")
    private val volumeUuid = "test-volume-uuid"
    private val appVersion = "1.0.0-test"
    private val wall = 1_700_000_000_000L
    private val mono = 12_345_000_000L
    private val segIndex = 7

    private fun fakeStore(
        dirAlreadyExists: Boolean = false,
        writingManifestOk: Boolean = true,
        finalManifestOk: Boolean = true,
    ) = FakeStoragePort(
        dirAlreadyExists = dirAlreadyExists,
        writingManifestOk = writingManifestOk,
        finalManifestOk = finalManifestOk,
    )

    private fun segDir() = SegmentLayout.segmentDir(rootDir, wall, segIndex)

    private fun writingFile(segmentDir: File, direction: String) =
        SegmentLayout.writingFile(SegmentLayout.videoFile(segmentDir, direction))

    private fun videoFile(segmentDir: File, direction: String) =
        SegmentLayout.videoFile(segmentDir, direction)

    /** 绑定正确事务身份的结果构造器（R1-4）。 */
    private fun sampleResult(
        tx: SegmentTransaction,
        direction: String,
        frameCount: Long = 100L,
        firstPtsUs: Long = 0L,
        lastPtsUs: Long = 3_960_000L, // 99 帧 * 40_000µs
        width: Int = 1280,
        height: Int = 800,
        fps: Int = 25,
        bitrateBps: Long = 2_000_000L,
        codecMime: String = "video/avc",
        encoderName: String = "c2.android.avc.encoder",
        implementation: String = "software",
        gopSeconds: Int = 1,
    ) = ChannelWriteResult(
        transactionId = tx.transactionId,
        direction = direction,
        codecMime = codecMime,
        encoderName = encoderName,
        implementation = implementation,
        width = width,
        height = height,
        fps = fps,
        bitrateBps = bitrateBps,
        gopSeconds = gopSeconds,
        frameCount = frameCount,
        firstPtsUs = firstPtsUs,
        lastPtsUs = lastPtsUs,
    )

    /** 身份不匹配（旧段/他事务）的结果构造器。 */
    private fun foreignResult(
        direction: String,
        transactionId: String = "foreign-tx-id",
        firstPtsUs: Long = 0L,
        lastPtsUs: Long = 3_960_000L,
    ) = ChannelWriteResult(
        transactionId = transactionId,
        direction = direction,
        codecMime = "video/avc",
        encoderName = "c2.android.avc.encoder",
        implementation = "software",
        width = 1280,
        height = 800,
        fps = 25,
        bitrateBps = 2_000_000L,
        gopSeconds = 1,
        frameCount = 100L,
        firstPtsUs = firstPtsUs,
        lastPtsUs = lastPtsUs,
    )

    // ---- 构造/begin 校验（构造期异常必须包进 try，R1-6）----

    @Test
    fun init_rejects_relative_root_path() {
        try {
            SegmentTransaction(File("relative/root"), volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, fakeStore())
            fail("应拒绝相对根路径")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("绝对路径"))
        }
    }

    @Test
    fun init_rejects_invalid_direction() {
        try {
            SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front", "up"), appVersion, fakeStore())
            fail("应拒绝非法方向")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("up"))
        }
    }

    @Test
    fun init_rejects_nonpositive_wall_clock() {
        try {
            SegmentTransaction(rootDir, volumeUuid, 0, mono, segIndex, listOf("front"), appVersion, fakeStore())
            fail("应拒绝非正墙钟")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("墙钟"))
        }
    }

    @Test
    fun init_rejects_negative_monotonic() {
        try {
            SegmentTransaction(rootDir, volumeUuid, wall, -1, segIndex, listOf("front"), appVersion, fakeStore())
            fail("应拒绝负单调时钟")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("单调"))
        }
    }

    @Test
    fun init_rejects_unsafe_root_path() {
        try {
            SegmentTransaction(File("/tmp/../etc/wbroot"), volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, fakeStore())
            fail("应拒绝含 .. 的不安全根路径")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不安全"))
        }
    }

    @Test
    fun begin_rejects_duplicate_segment() {
        // 已存在的段：ensureSegmentDir 独占创建失败 → begin 抛错（R1-2，去掉 TOCTOU 的 exists 预检）。
        val store = fakeStore(dirAlreadyExists = true)
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        try {
            tx.begin()
            fail("应拒绝复用同名段")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("段目录"))
        }
    }

    @Test
    fun begin_publishes_writing_manifest_with_four_directions() {
        val store = fakeStore()
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front", "rear"), appVersion, store)
        tx.begin()
        val m = store.lastManifest!!
        assertEquals(SegmentManifest.STATE_WRITING, m.state)
        // 固定四向：选定方向 writing，未选方向 missing。
        assertEquals(SegmentLayout.DIRECTIONS.toSet(), m.channels.map { it.direction }.toSet())
        assertEquals(4, m.channels.size)
        val front = m.channels.first { it.direction == "front" }
        val left = m.channels.first { it.direction == "left" }
        assertEquals(SegmentTransaction.CHANNEL_WRITING, front.status)
        assertEquals(SegmentManifest.CHANNEL_MISSING, left.status)
    }

    @Test
    fun commitChannel_before_begin_throws() {
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, fakeStore())
        try {
            tx.commitChannel(sampleResult(tx, "front"))
            fail("未 begin 不应提交")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("begin"))
        }
    }

    // ---- 真实写入结果校验（空视频 / 非法 PTS / 元数据）----

    @Test
    fun commitChannel_rejects_empty_video_file() {
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 0L
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val o = tx.commitChannel(sampleResult(tx, "front"))
        assertFalse(o.succeeded)
        assertTrue(o.reason!!.contains("为空"))
        assertNull(o.channel) // 失败不得伪造 channel 记录
        val out = tx.finish()
        assertEquals(SegmentManifest.STATE_FAILED, out.state)
        assertEquals(SegmentManifest.CHANNEL_MISSING, store.lastManifest!!.channels.first { it.direction == "front" }.status)
    }

    @Test
    fun commitChannel_rejects_io_error_reading_length() {
        val store = IoErrorLengthStore()
        val sd = segDir()
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val o = tx.commitChannel(sampleResult(tx, "front"))
        assertFalse(o.succeeded)
        assertTrue(o.reason!!.contains("I/O 错误"))
        assertNull(o.channel)
    }

    @Test
    fun commitChannel_rejects_negative_pts() {
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val o = tx.commitChannel(sampleResult(tx, "front", firstPtsUs = -1L))
        assertFalse(o.succeeded)
        assertTrue(o.reason!!.contains("PTS"))
    }

    @Test
    fun commitChannel_rejects_equal_pts() {
        // R1-4：首末相等 = 空区间，必须拒绝（不能当录像时长）。
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val o = tx.commitChannel(sampleResult(tx, "front", firstPtsUs = 1_000_000L, lastPtsUs = 1_000_000L))
        assertFalse(o.succeeded)
        assertTrue(o.reason!!.contains("区间为空"))
    }

    @Test
    fun commitChannel_rejects_nonmonotonic_pts() {
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val o = tx.commitChannel(sampleResult(tx, "front", firstPtsUs = 1_000_000L, lastPtsUs = 500_000L))
        assertFalse(o.succeeded)
        assertTrue(o.reason!!.contains("非单调"))
    }

    @Test
    fun commitChannel_rejects_zero_frames() {
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val o = tx.commitChannel(sampleResult(tx, "front", frameCount = 0L))
        assertFalse(o.succeeded)
        assertTrue(o.reason!!.contains("帧数"))
    }

    @Test
    fun commitChannel_rejects_unknown_implementation() {
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val o = tx.commitChannel(sampleResult(tx, "front", implementation = "gpu"))
        assertFalse(o.succeeded)
        assertTrue(o.reason!!.contains("实现三态"))
    }

    @Test
    fun commitChannel_rejects_blank_encoder_name() { // R1-5
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val o = tx.commitChannel(sampleResult(tx, "front", encoderName = "  "))
        assertFalse(o.succeeded)
        assertTrue(o.reason!!.contains("编码器名"))
    }

    @Test
    fun commitChannel_rejects_nonpositive_gop() { // R1-5
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val o = tx.commitChannel(sampleResult(tx, "front", gopSeconds = 0))
        assertFalse(o.succeeded)
        assertTrue(o.reason!!.contains("GOP"))
    }

    // ---- R1-4 事务身份：拒绝旧段/他事务结果混入 ----

    @Test
    fun commitChannel_rejects_other_transaction_result() {
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        // 同方向、但来自不同事务的迟到结果
        val o = tx.commitChannel(foreignResult("front"))
        assertFalse(o.succeeded)
        assertTrue(o.reason!!.contains("身份不匹配"))
        assertNull(o.channel)
        val out = tx.finish()
        assertEquals(SegmentManifest.STATE_FAILED, out.state)
    }

    @Test
    fun commitChannel_legal_repeat_returns_cached() {
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        store.commitVideoResults[videoFile(sd, "front")] = true
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val first = tx.commitChannel(sampleResult(tx, "front"))
        assertTrue(first.succeeded)
        val second = tx.commitChannel(sampleResult(tx, "front"))
        assertSame(first, second) // 合法重复：同一事务同方向，返回缓存，不重复 I/O
    }

    // ---- R1-1 完整性：固定四向，未选/未提交/失败统一标 MISSING ----

    @Test
    fun select4_commit1_is_partial_not_complete() {
        val store = fakeStore()
        val sd = segDir()
        for (d in listOf("front", "rear", "left", "right")) {
            store.writingFileLengths[writingFile(sd, d)] = 4096L
            store.commitVideoResults[videoFile(sd, d)] = true
        }
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front", "rear", "left", "right"), appVersion, store)
        tx.begin()
        tx.commitChannel(sampleResult(tx, "front")) // 仅提交 1 路
        val out = tx.finish()
        assertEquals(SegmentManifest.STATE_PARTIAL, out.state)
        assertFalse(out.isComplete)
        assertEquals(listOf("front"), out.completedDirections)
        assertEquals(setOf("rear", "left", "right"), out.failedDirections.toSet())
        val fm = store.lastManifest!!
        assertEquals(SegmentManifest.CHANNEL_COMPLETE, fm.channels.first { it.direction == "front" }.status)
        for (d in listOf("rear", "left", "right")) {
            assertEquals(SegmentManifest.CHANNEL_MISSING, fm.channels.first { it.direction == d }.status)
        }
        assertEquals(4, fm.channels.size)
    }

    @Test
    fun select1_commit1_is_partial_not_complete() {
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        store.commitVideoResults[videoFile(sd, "front")] = true
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        tx.commitChannel(sampleResult(tx, "front"))
        val out = tx.finish()
        assertEquals(SegmentManifest.STATE_PARTIAL, out.state)
        assertFalse(out.isComplete)
        val fm = store.lastManifest!!
        assertEquals(SegmentManifest.CHANNEL_COMPLETE, fm.channels.first { it.direction == "front" }.status)
        for (d in listOf("rear", "left", "right")) {
            assertEquals(SegmentManifest.CHANNEL_MISSING, fm.channels.first { it.direction == d }.status)
        }
    }

    @Test
    fun select4_one_fails_is_partial() {
        val store = fakeStore()
        val sd = segDir()
        for (d in listOf("front", "rear", "left", "right")) {
            store.writingFileLengths[writingFile(sd, d)] = 4096L
        }
        store.commitVideoResults[videoFile(sd, "front")] = false // 一路失败
        for (d in listOf("rear", "left", "right")) store.commitVideoResults[videoFile(sd, d)] = true

        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front", "rear", "left", "right"), appVersion, store)
        tx.begin()
        tx.commitChannel(sampleResult(tx, "front"))
        tx.commitChannel(sampleResult(tx, "rear"))
        tx.commitChannel(sampleResult(tx, "left"))
        tx.commitChannel(sampleResult(tx, "right"))

        // 失败路的 writing 证据仍在（未删除）
        assertTrue(store.fileExists(writingFile(sd, "front")))
        val out = tx.finish()
        assertEquals(SegmentManifest.STATE_PARTIAL, out.state)
        assertFalse(out.isComplete)
        assertEquals(setOf("rear", "left", "right"), out.completedDirections.toSet())
        assertEquals(listOf("front"), out.failedDirections)
        val fm = store.lastManifest!!
        assertEquals(SegmentManifest.CHANNEL_COMPLETE, fm.channels.first { it.direction == "rear" }.status)
        assertEquals(SegmentManifest.CHANNEL_MISSING, fm.channels.first { it.direction == "front" }.status)
    }

    @Test
    fun all4_complete_yields_complete_manifest() {
        val store = fakeStore()
        val sd = segDir()
        for (d in listOf("front", "rear", "left", "right")) {
            store.writingFileLengths[writingFile(sd, d)] = 4096L
            store.commitVideoResults[videoFile(sd, d)] = true
        }
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front", "rear", "left", "right"), appVersion, store)
        tx.begin()
        for (d in listOf("front", "rear", "left", "right")) {
            assertTrue(tx.commitChannel(sampleResult(tx, d)).succeeded)
        }
        val out = tx.finish()
        assertEquals(SegmentManifest.STATE_COMPLETE, out.state)
        assertTrue(out.isComplete)
        assertEquals(setOf("front", "rear", "left", "right"), out.completedDirections.toSet())
        val fm = store.lastManifest!!
        assertEquals(SegmentManifest.STATE_COMPLETE, fm.state)
        assertTrue(fm.channels.all { it.status == SegmentManifest.CHANNEL_COMPLETE })
        assertTrue(fm.isComplete)
        // 时长 = max(lastPtsUs)/1000（段相对域），sampleResult 默认 last=3_960_000 → 3960ms。
        assertEquals(3960L, fm.durationMillis)
    }

    // ---- R2 裁决：段相对 PTS 保留起始缺口（Lead 边界反例口径），不减去各路首帧 ----

    @Test
    fun duration_preserves_segment_start_gap() {
        // R2 裁决：文件 PTS 为段相对时间轴；首帧晚于段起点 1s 的缺口属于段内容，
        // 时长 = max(lastPtsUs)/1000 = 3960ms，而不是 (末−首)=2960ms 裁掉缺口。
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        store.commitVideoResults[videoFile(sd, "front")] = true
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        tx.commitChannel(sampleResult(tx, "front", firstPtsUs = 1_000_000L, lastPtsUs = 3_960_000L))
        val out = tx.finish()
        assertEquals(SegmentManifest.STATE_PARTIAL, out.state)
        assertEquals(3960L, store.lastManifest!!.durationMillis) // 保留 1s 起始缺口：max(lastPtsUs)/1000
    }

    // ---- R1-6 清单失败：writing / final 分别注入 ----

    @Test
    fun final_manifest_write_failure_preserves_evidence_and_does_not_fake_complete() {
        val store = fakeStore(finalManifestOk = false) // writing 清单成功，仅最终清单失败
        val sd = segDir()
        for (d in listOf("front", "rear")) {
            store.writingFileLengths[writingFile(sd, d)] = 4096L
            store.commitVideoResults[videoFile(sd, d)] = true
        }
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front", "rear"), appVersion, store)
        tx.begin()
        tx.commitChannel(sampleResult(tx, "front"))
        tx.commitChannel(sampleResult(tx, "rear"))
        val out = tx.finish()
        assertFalse(out.manifestWritten)
        assertFalse(out.isComplete)
        assertNotNull(out.error)
        assertTrue(store.commitVideoCount > 0)
        assertTrue(store.fileExists(videoFile(sd, "front")))
        assertTrue(store.fileExists(videoFile(sd, "rear")))
    }

    @Test
    fun writing_manifest_write_failure_makes_begin_fail() {
        val store = fakeStore(writingManifestOk = false)
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        try {
            tx.begin()
            fail("writing 清单失败应使 begin 抛错")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("writing 清单"))
        }
        assertFalse(tx.isTerminated())
        assertTrue(tx.isFailed())
        // 不可复用：再次 begin 仍报错
        try {
            tx.begin()
            fail("begin 失败后不应可再次 begin")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("FAILED"))
        }
    }

    // ---- R1-3 生命周期 / 异常 / 幂等 ----

    @Test
    fun abort_keeps_evidence_partial_when_some_committed() {
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        store.commitVideoResults[videoFile(sd, "front")] = true
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front", "rear"), appVersion, store)
        tx.begin()
        tx.commitChannel(sampleResult(tx, "front"))
        val out = tx.abort("测试中止")
        assertEquals(SegmentManifest.STATE_PARTIAL, out.state)
        assertEquals(setOf("front"), out.completedDirections.toSet())
        assertTrue(out.failedDirections.containsAll(listOf("rear", "left", "right")))
        assertTrue(store.fileExists(videoFile(sd, "front")))
    }

    @Test
    fun repeat_finish_is_idempotent() {
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        store.commitVideoResults[videoFile(sd, "front")] = true
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        tx.commitChannel(sampleResult(tx, "front"))
        val first = tx.finish()
        val writesAfterFirst = store.manifestWriteCount
        val second = tx.finish()
        assertEquals(first.state, second.state)
        assertEquals(writesAfterFirst, store.manifestWriteCount) // 第二次不重复写清单
        assertTrue(tx.isTerminated())
    }

    @Test
    fun abort_then_finish_is_idempotent() {
        val store = fakeStore()
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val aborted = tx.abort()
        val writesAfterAbort = store.manifestWriteCount
        val afterFinish = tx.finish()
        assertEquals(aborted.state, afterFinish.state)
        assertEquals(writesAfterAbort, store.manifestWriteCount)
    }

    @Test
    fun abort_before_begin_throws() {
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, fakeStore())
        try {
            tx.abort()
            fail("未 begin 不应 abort")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("begin"))
        }
    }

    @Test
    fun close_before_begin_is_noop() {
        val store = fakeStore()
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.close() // PENDING 态空操作，不创建任何文件
        assertEquals(0, store.manifestWriteCount)
        assertFalse(store.createdDirs.contains(segDir()))
        assertFalse(tx.isTerminated())
    }

    @Test
    fun begin_failure_makes_transaction_unusable() {
        val store = fakeStore(writingManifestOk = false)
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        try {
            tx.begin()
            fail("应抛错")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("writing 清单"))
        }
        assertFalse(tx.isTerminated())
        assertTrue(tx.isFailed())
        try {
            tx.begin()
            fail("失败事务不可复用")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("FAILED"))
        }
        try {
            tx.commitChannel(sampleResult(tx, "front"))
            fail("失败事务不可提交")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("WRITING"))
        }
    }

    @Test
    fun begin_port_exception_marks_failed() {
        val store = ThrowingBeginStore() // writing 清单写出时抛异常
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        try {
            tx.begin()
            fail("端口异常应使 begin 抛错")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("writing 清单"))
        }
        assertTrue(tx.isFailed())
    }

    @Test
    fun finish_port_exception_records_failure_not_throws() {
        val store = ThrowingManifestStore() // 最终清单写出时抛异常
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        tx.commitChannel(sampleResult(tx, "front"))
        val out = tx.finish() // 必须不抛
        assertFalse(out.manifestWritten)
        assertNotNull(out.error)
        assertTrue(out.error!!.contains("异常"))
        assertTrue(tx.isTerminated())
        assertFalse(out.isComplete)
    }

    @Test
    fun abort_port_exception_records_failure() {
        val store = ThrowingManifestStore()
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        tx.commitChannel(sampleResult(tx, "front"))
        val out = tx.abort("测试")
        assertFalse(out.manifestWritten)
        assertNotNull(out.error)
        assertTrue(out.error!!.contains("异常"))
        assertTrue(tx.isTerminated())
    }

    // ---- 单一写线程所有权 ----

    @Test
    fun commitChannel_enforces_single_writer_thread() {
        val store = fakeStore()
        val sd = segDir()
        store.writingFileLengths[writingFile(sd, "front")] = 4096L
        store.commitVideoResults[videoFile(sd, "front")] = true
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val err = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        val t = Thread {
            try {
                tx.commitChannel(sampleResult(tx, "front"))
            } catch (e: Throwable) {
                err.set(e)
            } finally {
                latch.countDown()
            }
        }
        t.start()
        latch.await(5, TimeUnit.SECONDS)
        assertNotNull("应拒绝非 owner 线程提交", err.get())
        assertTrue(err.get() is IllegalStateException)
        assertTrue(err.get()!!.message!!.contains("写线程"))
    }

    // ---- 提交异常不吞掉，记录为失败并保留证据 ----

    @Test
    fun commitChannel_port_exception_recorded_as_failure_not_swallowed() {
        val store = ThrowingCommitStore()
        val tx = SegmentTransaction(rootDir, volumeUuid, wall, mono, segIndex, listOf("front"), appVersion, store)
        tx.begin()
        val o = tx.commitChannel(sampleResult(tx, "front", frameCount = 100))
        assertFalse(o.succeeded)
        assertTrue(o.reason!!.contains("异常"))
    }

    // ---- R1-2 原子独占创建（真实临时目录）----

    @Test
    fun real_segment_dir_atomic_exclusive_create() {
        val realStore = RealSegmentStorePort()
        val base = Files.createTempDirectory("wb-atomic").toFile()
        val sd = SegmentLayout.segmentDir(base, wall, segIndex)
        assertTrue("首次创建应成功", realStore.ensureSegmentDir(sd))
        assertFalse("已存在则独占创建必须失败", realStore.ensureSegmentDir(sd))
        // 模拟已有的"原清单"，验证第二次创建失败后原清单未被覆盖
        val manifestFile = File(sd, SegmentLayout.FILE_MANIFEST)
        manifestFile.writeText("original-manifest")
        assertFalse(realStore.ensureSegmentDir(sd))
        assertEquals("original-manifest", manifestFile.readText())
    }

    // ---- 假端口实现 ----

    /** 模拟失败的模式端口：区分 writing/final 清单失败，独占创建，追踪已建目录与视频证据。 */
    private class FakeStoragePort(
        private val dirAlreadyExists: Boolean,
        private val writingManifestOk: Boolean,
        private val finalManifestOk: Boolean,
    ) : SegmentStorePort {
        val writingFileLengths = mutableMapOf<File, Long>()
        val commitVideoResults = mutableMapOf<File, Boolean>()
        val committedVideos = mutableSetOf<File>()
        val createdDirs = mutableSetOf<File>()
        var manifestWriteCount = 0
        var commitVideoCount = 0
        var lastManifest: SegmentManifest? = null

        override fun segmentDirExists(dir: File) = dir in createdDirs

        override fun ensureSegmentDir(dir: File): Boolean {
            if (dirAlreadyExists || dir in createdDirs) return false // 独占：已存在即失败
            createdDirs.add(dir)
            return true
        }

        override fun writeManifest(segmentDir: File, manifest: SegmentManifest): Boolean {
            manifestWriteCount++
            // 第 1 次（begin 的 writing 清单）用 writingManifestOk；第 2 次（终止清单）用 finalManifestOk。
            val ok = if (manifestWriteCount == 1) writingManifestOk else finalManifestOk
            if (ok) lastManifest = manifest
            return ok
        }

        override fun commitVideo(video: File): Boolean {
            commitVideoCount++
            val ok = commitVideoResults[video] ?: true
            if (ok) committedVideos.add(video)
            return ok
        }

        override fun fileLength(file: File): Long = writingFileLengths[file] ?: 0L

        override fun fileExists(file: File): Boolean = file in committedVideos || file in writingFileLengths
    }

    /** fileLength 直接抛 I/O 异常，验证空文件与 I/O 错误区分。 */
    private class IoErrorLengthStore : SegmentStorePort {
        override fun segmentDirExists(dir: File) = false
        override fun ensureSegmentDir(dir: File) = true
        override fun writeManifest(segmentDir: File, manifest: SegmentManifest) = true
        override fun commitVideo(video: File): Boolean = true
        override fun fileLength(file: File): Long = throw RuntimeException("模拟磁盘 I/O 错误")
        override fun fileExists(file: File) = true
    }

    /** commitVideo 直接抛异常的端口，验证异常不吞掉、被记成失败。 */
    private class ThrowingCommitStore : SegmentStorePort {
        override fun segmentDirExists(dir: File) = false
        override fun ensureSegmentDir(dir: File) = true
        override fun writeManifest(segmentDir: File, manifest: SegmentManifest) = true
        override fun commitVideo(video: File): Boolean = throw RuntimeException("模拟 fsync 崩溃")
        override fun fileLength(file: File) = 4096L
        override fun fileExists(file: File) = true
    }

    /** begin 的 writing 清单写出时抛异常的端口。 */
    private class ThrowingBeginStore : SegmentStorePort {
        override fun segmentDirExists(dir: File) = false
        override fun ensureSegmentDir(dir: File) = true
        override fun writeManifest(segmentDir: File, manifest: SegmentManifest): Boolean = throw RuntimeException("模拟清单写出崩溃")
        override fun commitVideo(video: File): Boolean = true
        override fun fileLength(file: File) = 4096L
        override fun fileExists(file: File) = true
    }

    /** 终止清单（finish/abort，即第 2 次 writeManifest）写出时抛异常的端口；begin 的 writing 清单不受影响。 */
    private class ThrowingManifestStore : SegmentStorePort {
        private var writes = 0
        override fun segmentDirExists(dir: File) = false
        override fun ensureSegmentDir(dir: File) = true
        override fun writeManifest(segmentDir: File, manifest: SegmentManifest): Boolean {
            if (++writes >= 2) throw RuntimeException("模拟清单写出崩溃")
            return true
        }
        override fun commitVideo(video: File): Boolean = true
        override fun fileLength(file: File) = 4096L
        override fun fileExists(file: File) = true
    }
}
