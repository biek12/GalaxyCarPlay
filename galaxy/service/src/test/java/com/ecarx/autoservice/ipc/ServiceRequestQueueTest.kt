package com.ecarx.autoservice.ipc

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 用真实 main Handler 和 Robolectric 主 Looper 跑生产 [ServiceRequestQueue]。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ServiceRequestQueueTest {
    private val main = Handler(Looper.getMainLooper())

    @Test
    fun `入队不立即执行并保持FIFO`() {
        val order = mutableListOf<String>()
        val queue = ServiceRequestQueue(main, isClosed = { false }, onFailure = { _, _ -> error("成功路径不应报错") })
        queue.enqueue("a") { order += "a" }
        queue.enqueue("b") { order += "b" }
        assertTrue(order.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("a", "b"), order)
    }

    @Test
    fun `关闭状态在任务实际执行时读取`() {
        var closed = false
        var ran = false
        val queue = ServiceRequestQueue(main, isClosed = { closed }, onFailure = { _, _ -> error("跳过时不应报错") })
        queue.enqueue("later-closed") { ran = true }
        closed = true
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ran)

        closed = true
        queue.enqueue("closed-then-open") { ran = true }
        closed = false
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ran)
    }

    @Test
    fun `失败只回调一次并带回入队前的来源和同一异常`() {
        val captured = mutableListOf<Pair<String, Throwable>>()
        val queue =
            ServiceRequestQueue(
                main,
                isClosed = { false },
                onFailure = { source, error -> captured += source to error },
            )
        var source = "before"
        val failure = IllegalStateException("boom")
        queue.enqueue(source) { throw failure }
        source = "after"
        queue.enqueue("ok") { }
        assertTrue(captured.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, captured.size)
        assertEquals("before", captured.single().first)
        assertSame(failure, captured.single().second)
    }
}
