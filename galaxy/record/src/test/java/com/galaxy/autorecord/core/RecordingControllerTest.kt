package com.galaxy.autorecord.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

/**
 * 两阶段预检的状态机行为（规格 v1.1 §14.2）。
 *
 * 重点不是"能不能开始"，而是**失败时必须列出失败项、且绝不自动降级**：
 * 引擎未接入、某一路没通道、编码器缺失，都要被用户看见。
 */
@RunWith(RobolectricTestRunner::class)
class RecordingControllerTest {

    private class FakeEnv(
        override val engineAvailable: Boolean,
        private val statuses: Map<String, ChannelStatus>,
        private val storageFailure: PreflightFailure? = null,
        private val codecFailure: PreflightFailure? = null,
    ) : RecordingEnvironment {
        override fun checkStorage(settings: RecordSettings): PreflightFailure? = storageFailure
        override fun checkEncoder(settings: RecordSettings): PreflightFailure? = codecFailure
        override fun channels(): Map<String, ChannelStatus> = statuses
    }

    private val direct = Executor { it.run() }

    private fun liveAll(): Map<String, ChannelStatus> =
        SegmentLayout.DIRECTIONS.associateWith { ChannelStatus(it, CameraState.ACTIVE) }

    private fun controller(env: RecordingEnvironment) =
        RecordingController(env, { RecordSettings() }, direct)

    @Test
    fun `引擎未接入时预检必须失败并指出引擎阶段`() {
        val controller = controller(FakeEnv(engineAvailable = false, statuses = liveAll()))
        var outcome: PreflightOutcome? = null
        controller.preflightListener = { outcome = it }

        controller.requestStart()

        val failed = outcome as PreflightOutcome.Failed
        assertTrue(failed.failures.any { it.stage == "engine" })
        assertEquals(RecordingState.ERROR, controller.snapshot.state)
        assertTrue(controller.snapshot.error?.detail?.contains("录制引擎") == true)
    }

    @Test
    fun `四路通道就绪且引擎可用才通过`() {
        val controller = controller(FakeEnv(engineAvailable = true, statuses = liveAll()))
        var outcome: PreflightOutcome? = null
        controller.preflightListener = { outcome = it }

        controller.requestStart()

        val passed = outcome as PreflightOutcome.Passed
        assertEquals(4, passed.directions.size)
    }

    @Test
    fun `缺一路时失败项必须能定位到方向`() {
        val statuses = liveAll().toMutableMap()
        statuses["left"] = ChannelStatus("left", CameraState.WAITING_VENDOR_RELEASE, "等待原厂释放")
        val controller = controller(FakeEnv(engineAvailable = true, statuses = statuses))
        var outcome: PreflightOutcome? = null
        controller.preflightListener = { outcome = it }

        controller.requestStart()

        val failed = outcome as PreflightOutcome.Failed
        val left = failed.failures.first { it.direction == "left" }
        assertEquals("channel", left.stage)
        assertTrue(left.detail.contains("原厂"))
    }

    @Test
    fun `存储不可写时预检失败且带原因`() {
        val controller = controller(
            FakeEnv(
                engineAvailable = true,
                statuses = liveAll(),
                storageFailure = PreflightFailure(null, "storage", "「U 盘」已移除，未自动切换介质"),
            ),
        )
        var outcome: PreflightOutcome? = null
        controller.preflightListener = { outcome = it }

        controller.requestStart()

        val failed = outcome as PreflightOutcome.Failed
        assertTrue(failed.failures.any { it.stage == "storage" && it.detail.contains("已移除") })
    }

    @Test
    fun `用户显式选择仅录可用路也不会伪造录制中`() {
        val controller = controller(FakeEnv(engineAvailable = false, statuses = liveAll()))
        controller.requestStart()
        controller.startWithDirections(listOf("front", "rear"))

        // 引擎未接入：状态仍是 ERROR，绝不出现 RECORDING
        assertEquals(RecordingState.ERROR, controller.snapshot.state)
        assertEquals(CameraState.WAITING_FIRST_FRAME, controller.snapshot.cameras["front"])
        assertEquals(CameraState.DISCONNECTED, controller.snapshot.cameras["left"])
    }

    @Test
    fun `停止后回到未录制并清空累计量`() {
        val controller = controller(FakeEnv(engineAvailable = false, statuses = liveAll()))
        controller.requestStart()
        controller.requestStop()

        assertEquals(RecordingState.IDLE, controller.snapshot.state)
        assertEquals(0L, controller.snapshot.bytesWritten)
    }

    @Test
    fun `非录制状态不推进片段计时`() {
        val controller = controller(FakeEnv(engineAvailable = false, statuses = liveAll()))
        controller.tick(System.currentTimeMillis() + 5_000)
        assertEquals(0L, controller.snapshot.segmentElapsedMillis)
    }

    @Test
    fun `工作线程预检不能直接发布结果且停止后的旧结果不得覆盖空闲状态`() {
        val work = ArrayDeque<Runnable>()
        val callbacks = ArrayDeque<Runnable>()
        val controller = RecordingController(
            FakeEnv(false, liveAll()), { RecordSettings() },
            Executor { work.add(it) }, Executor { callbacks.add(it) },
        )
        val delivered = mutableListOf<PreflightOutcome>()
        controller.preflightListener = { delivered += it }
        controller.requestStart()
        work.removeFirst().run()
        assertEquals(RecordingState.STARTING, controller.snapshot.state)
        assertTrue(delivered.isEmpty())
        controller.requestStop()
        callbacks.removeFirst().run()
        assertEquals(RecordingState.IDLE, controller.snapshot.state)
        assertTrue(delivered.isEmpty())
        controller.requestStart()
        work.removeFirst().run()
        callbacks.removeFirst().run()
        assertEquals(RecordingState.ERROR, controller.snapshot.state)
        assertTrue(delivered.single() is PreflightOutcome.Failed)
    }

    @Test
    fun `宿主销毁后不交付排队预检结果也不再接受启动`() {
        val work = ArrayDeque<Runnable>()
        val callbacks = ArrayDeque<Runnable>()
        val controller = RecordingController(
            FakeEnv(false, liveAll()), { RecordSettings() },
            Executor { work.add(it) }, Executor { callbacks.add(it) },
        )
        var notifications = 0
        controller.preflightListener = { notifications++ }
        controller.requestStart()
        work.removeFirst().run()
        controller.close()
        callbacks.removeFirst().run()
        controller.requestStart()
        assertEquals(0, notifications)
        assertTrue(work.isEmpty())
    }
}
