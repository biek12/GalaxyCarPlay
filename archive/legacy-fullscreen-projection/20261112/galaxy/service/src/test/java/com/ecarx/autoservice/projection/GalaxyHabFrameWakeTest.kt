package com.ecarx.autoservice.projection

import org.junit.Assert.*
import org.junit.Test

class GalaxyHabFrameWakeTest {
    private class Queue {
        val tasks = mutableListOf<Pair<Runnable, Long>>()
        val wake = GalaxyHabFrameWake(
            post = { task, delay -> tasks.add(task to delay); Unit },
            remove = { task -> tasks.removeAll { it.first === task }; Unit },
        )
        fun runNext() = tasks.removeAt(0).first.run()
    }
    @Test fun newFramesReplaceHeartbeatWaitAndCoalesceDuringSlowWrite() {
        val q = Queue()
        var sends = 0
        q.wake.start {
            ++sends
            if (sends == 1) repeat(100) { q.wake.wake() } // 模拟一次慢write期间采集持续到帧。
            q.wake.idle(1000)
        }
        q.wake.idle(1000)
        repeat(100) { q.wake.wake() }
        assertEquals(1, q.tasks.size)
        assertEquals(0L, q.tasks.single().second)
        q.runNext()
        assertEquals(1, sends)
        assertEquals(1, q.tasks.size)
        assertEquals(0L, q.tasks.single().second) // 不能被发送后的心跳等待延后。
        q.runNext()
        assertEquals(2, sends)
        assertEquals(1000L, q.tasks.single().second)
    }
    @Test fun stoppedOrOldGenerationCallbacksCannotWakeNewSession() {
        val q = Queue()
        var oldCalls = 0
        var newCalls = 0
        q.wake.start { ++oldCalls }
        q.wake.wake()
        val late = q.tasks.single().first
        q.wake.stop()
        late.run()
        q.wake.wake()
        assertTrue(q.tasks.isEmpty())
        q.wake.start { ++newCalls }
        q.wake.wake()
        late.run()
        repeat(100) { q.wake.wake() }
        assertEquals(1, q.tasks.size)
        q.runNext()
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)
    }
}
