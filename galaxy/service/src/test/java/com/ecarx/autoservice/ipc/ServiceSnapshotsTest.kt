package com.ecarx.autoservice.ipc

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.galaxy.auto.contract.IAutoCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 用生产 [ServiceSnapshots] 和 Robolectric 主 Handler 验证缓存、合并与回调隔离。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ServiceSnapshotsTest {
    private val main = Handler(Looper.getMainLooper())

    @Test
    fun `初始为空且返回的顶层修改不写回缓存`() {
        val owner = owner(isClosed = { false })
        try {
            assertTrue(owner.current().isEmpty)
            val published = Bundle().apply { putString("status", "ready") }
            owner.publish(published, emptyList())
            val read = owner.current()
            assertNotSame(published, read)
            assertEquals("ready", read.getString("status"))
            read.putString("status", "mutated")
            assertEquals("ready", owner.current().getString("status"))
            assertEquals(setOf("status"), owner.current().keySet())
        } finally {
            main.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun `执行前连续发布只投递最新一轮且不清除无关任务`() {
        val posted = mutableListOf<Runnable>()
        val removed = mutableListOf<Runnable>()
        val received = mutableListOf<Pair<String, String?>>()
        var otherRan = false
        val owner =
            ServiceSnapshots(
                post = { action ->
                    posted += action
                    main.post(action)
                },
                remove = { action ->
                    removed += action
                    main.removeCallbacks(action)
                },
                isClosed = { false },
            )
        val first = callback { snapshot -> received += "first" to snapshot.getString("status") }
        val second = callback { snapshot -> received += "second" to snapshot.getString("status") }
        try {
            owner.publish(Bundle().apply { putString("status", "one") }, listOf(first))
            main.post { otherRan = true }
            owner.publish(Bundle().apply { putString("status", "two") }, listOf(second))
            assertTrue(received.isEmpty())
            assertFalse(otherRan)
            assertEquals("two", owner.current().getString("status"))
            assertEquals(2, posted.size)
            assertSame(posted[0], posted[1])
            assertEquals(2, removed.size)
            assertSame(posted[0], removed[0])
            assertSame(posted[0], removed[1])
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("second" to "two"), received)
            assertTrue(otherRan)
        } finally {
            main.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun `前一位改包并抛异常时后一位仍收到原值且缓存不变`() {
        val seen = mutableListOf<String>()
        val owner = owner(isClosed = { false })
        val first =
            callback { snapshot ->
                seen += snapshot.getString("status").orEmpty()
                snapshot.putString("status", "changed-by-first")
                throw IllegalStateException("first-failed")
            }
        val second =
            callback { snapshot ->
                seen += snapshot.getString("status").orEmpty()
            }
        try {
            owner.publish(Bundle().apply { putString("status", "ok") }, listOf(first, second))
            assertTrue(seen.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("ok", "ok"), seen)
            assertEquals("ok", owner.current().getString("status"))
        } finally {
            main.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun `已排队后关闭不发送并且空列表撤掉待发通知`() {
        var closed = false
        var closedReads = 0
        var calls = 0
        val owner =
            owner(
                isClosed = {
                    closedReads += 1
                    closed
                },
            )
        val recipient = callback { calls += 1 }
        try {
            owner.publish(Bundle().apply { putString("status", "queued") }, listOf(recipient))
            assertEquals(0, calls)
            assertEquals(0, closedReads)
            closed = true
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, calls)
            assertEquals(1, closedReads)

            closed = false
            closedReads = 0
            owner.publish(Bundle().apply { putString("status", "pending") }, listOf(recipient))
            owner.publish(Bundle().apply { putString("status", "cleared") }, emptyList())
            assertEquals(0, calls)
            assertEquals(0, closedReads)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, calls)
            assertEquals(0, closedReads)
            assertEquals("cleared", owner.current().getString("status"))
        } finally {
            main.removeCallbacksAndMessages(null)
        }
    }

    private fun owner(isClosed: () -> Boolean): ServiceSnapshots =
        ServiceSnapshots(
            post = { action -> main.post(action) },
            remove = { action -> main.removeCallbacks(action) },
            isClosed = isClosed,
        )

    private fun callback(onSnapshot: (Bundle) -> Unit): IAutoCallback =
        object : IAutoCallback.Stub() {
            override fun onSnapshot(snapshot: Bundle) = onSnapshot(snapshot)

            override fun onSurface(
                role: String,
                surface: Surface,
            ) = Unit

            override fun onProjectionState(
                role: String,
                state: String,
            ) = Unit

            override fun onVehicleFeed(values: Bundle) = Unit
        }
}
