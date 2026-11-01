package com.ecarx.autoservice.vehicle

import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 轮询链唯一性（审计第4条）。
 *
 * 脱离真实车机也能验证的判据：同一任务被反复排入只能执行一次。修复前每拍用新的lambda
 * `postDelayed`，Handler不去重 → n次订阅更新就留下n条并行链，Root IPC/Bundle/回调成倍上升。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class PollScheduleTest {
    private val main = Handler(Looper.getMainLooper())

    @Test fun reschedulingReplacesPendingTickInsteadOfQueueingSecondChain() {
        var runs = 0
        val schedule = PollSchedule(Runnable { ++runs })
        repeat(5) { schedule.schedule(main, 1000) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5000))
        assertEquals("5次排程只应执行1次；多执行就是多条轮询链", 1, runs)
        // 再等一个周期也不该冒出残留链。
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5000))
        assertEquals(1, runs)
    }

    @Test fun cancelStopsTheOnlyPendingTick() {
        var runs = 0
        val schedule = PollSchedule(Runnable { ++runs })
        schedule.schedule(main, 1000)
        schedule.cancel(); schedule.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5000))
        assertEquals(0, runs)
    }

    /** 退订取消之后重新订阅必须照常排上（不能把“已取消”记成永久失效）。 */
    @Test fun scheduleAfterCancelStillRuns() {
        var runs = 0
        val schedule = PollSchedule(Runnable { ++runs })
        schedule.schedule(main, 1000); schedule.cancel()
        schedule.schedule(main, 1000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5000))
        assertEquals(1, runs)
    }
}