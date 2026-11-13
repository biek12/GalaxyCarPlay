package com.ecarx.autoservice.vehicle

import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.ecarx.autoservice.ipc.ClientRegistry
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 用生产 [VehicleFeedDispatcher]、生产 [ClientRegistry.attach] 和 Robolectric 主 Handler 验证缓存与扇出。
 * 时间用显式 at 推进。不接车辆源，不另写节流或序号模型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VehicleFeedDispatcherTest {
    private val main = Handler(Looper.getMainLooper())

    @Test
    fun `禁用发布不触碰缓存和边界，无人订阅时仍先写缓存再接受再读租约`() {
        val trace = mutableListOf<String>()
        val deliveries = mutableListOf<Delivery>()
        var enabled = false
        val registry = ClientRegistry {}
        lateinit var dispatcher: VehicleFeedDispatcher
        dispatcher =
            VehicleFeedDispatcher(
                post = { action ->
                    trace += "post"
                    main.post(action)
                },
                isEnabled = {
                    trace += "enabled"
                    enabled
                },
                acceptValues = {
                    val speed =
                        dispatcher
                            .select(setOf("displaySpeed"))
                            .getBundle("displaySpeed")
                            ?.getString("value")
                    trace += "accept:$speed"
                },
                leases = {
                    trace += "leases:${registry.all().size}"
                    registry.all()
                },
            )
        try {
            dispatcher.publish(values("1"), 500L)
            assertEquals(listOf("enabled"), trace)
            assertTrue(dispatcher.select(setOf("displaySpeed")).isEmpty)
            assertTrue(dispatcher.snapshot().isEmpty)

            enabled = true
            dispatcher.publish(values("42"), 1_000L)
            assertEquals(listOf("enabled", "enabled", "accept:42", "leases:0"), trace)
            assertEquals("42", speedOf(dispatcher.select(setOf("displaySpeed"))))

            val lease =
                attach(registry, AutoContract.MAP, 10001) { bundle ->
                    deliveries += delivery("map", bundle)
                }
            lease.feed = setOf("displaySpeed")
            lease.feedIntervalMillis = 1_000
            dispatcher.replay(lease, 1_000L)
            assertEquals(0L, lease.lastFeedAt)
            assertEquals(listOf("enabled", "enabled", "accept:42", "leases:0", "post"), trace)
            assertTrue(deliveries.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(Delivery("map", "42", 1_000L, 1L)), deliveries)
        } finally {
            registry.close()
            main.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun `同一轮共享序号，未到期仍占号，缺键不发空包`() {
        val postMarks = mutableListOf<String>()
        val deliveries = mutableListOf<Delivery>()
        val registry = ClientRegistry {}
        lateinit var fast: ClientRegistry.Lease
        lateinit var slow: ClientRegistry.Lease
        val dispatcher =
            VehicleFeedDispatcher(
                post = { action ->
                    postMarks += "${fast.lastFeedAt}/${slow.lastFeedAt}"
                    main.post(action)
                },
                isEnabled = { true },
                acceptValues = {},
                leases = { registry.all() },
            )
        try {
            fast =
                attach(registry, AutoContract.MAP, 10001) { bundle ->
                    deliveries += delivery("fast", bundle)
                }
            slow =
                attach(registry, AutoContract.OBSERVER, 10002) { bundle ->
                    deliveries += delivery("slow", bundle)
                }
            fast.feed = setOf("displaySpeed")
            fast.feedIntervalMillis = 250
            slow.feed = setOf("displaySpeed")
            slow.feedIntervalMillis = 1_000
            assertFalse(fast.active)
            assertFalse(slow.active)

            dispatcher.publish(values("42"), 10_000L)
            dispatcher.publish(values("43"), 10_249L)
            assertEquals("43", speedOf(dispatcher.select(setOf("displaySpeed"))))
            assertEquals(10_000L, fast.lastFeedAt)
            assertEquals(10_000L, slow.lastFeedAt)
            assertEquals(listOf("10000/0", "10000/10000"), postMarks)

            dispatcher.publish(values("44"), 10_250L)
            assertEquals(10_250L, fast.lastFeedAt)
            assertEquals(10_000L, slow.lastFeedAt)

            slow.feed = setOf("gear")
            dispatcher.publish(values("50"), 20_000L)
            assertEquals(20_000L, fast.lastFeedAt)
            assertEquals(20_000L, slow.lastFeedAt)
            assertEquals(setOf("gear"), slow.feed)
            assertEquals(
                listOf("10000/0", "10000/10000", "10250/10000", "20000/10000"),
                postMarks,
            )
            assertTrue(deliveries.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                listOf(
                    Delivery("fast", "42", 10_000L, 1L),
                    Delivery("slow", "42", 10_000L, 1L),
                    Delivery("fast", "44", 10_250L, 3L),
                    Delivery("fast", "50", 20_000L, 4L),
                ),
                deliveries,
            )
        } finally {
            registry.close()
            main.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun `补发不受开关和间隔影响，清空只丢掉缓存`() {
        val trace = mutableListOf<String>()
        val deliveries = mutableListOf<Delivery>()
        var enabled = true
        val registry = ClientRegistry {}
        val dispatcher =
            VehicleFeedDispatcher(
                post = { action ->
                    trace += "post"
                    main.post(action)
                },
                isEnabled = {
                    trace += "enabled"
                    enabled
                },
                acceptValues = { trace += "accept" },
                leases = {
                    trace += "leases"
                    registry.all()
                },
            )
        try {
            val lease =
                attach(registry, AutoContract.MAP, 10011) { bundle ->
                    deliveries += delivery("map", bundle)
                }
            lease.feed = setOf("displaySpeed")
            lease.feedIntervalMillis = 1_000
            dispatcher.publish(values("42"), 1_000L)
            enabled = false
            dispatcher.replay(lease, 1_000L)
            assertEquals(1_000L, lease.lastFeedAt)
            assertEquals(listOf("enabled", "accept", "leases", "post", "post"), trace)

            dispatcher.clear()
            assertTrue(dispatcher.select(setOf("displaySpeed")).isEmpty)
            assertTrue(dispatcher.snapshot().isEmpty)
            assertEquals(setOf("displaySpeed"), lease.feed)
            assertEquals(1_000L, lease.lastFeedAt)
            assertSame(lease, registry.all().single())
            dispatcher.replay(lease, 1_000L)
            assertEquals(listOf("enabled", "accept", "leases", "post", "post"), trace)

            enabled = true
            dispatcher.publish(values("7"), 5_000L)
            assertEquals("7", speedOf(dispatcher.select(setOf("displaySpeed"))))
            assertEquals(5_000L, lease.lastFeedAt)
            assertEquals(setOf("displaySpeed"), lease.feed)
            assertTrue(deliveries.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                listOf(
                    Delivery("map", "42", 1_000L, 1L),
                    Delivery("map", "42", 1_000L, 2L),
                    Delivery("map", "7", 5_000L, 4L),
                ),
                deliveries,
            )
        } finally {
            registry.close()
            main.removeCallbacksAndMessages(null)
        }
    }

    @Test
    fun `拉取和诊断副本隔离，异步回调保留入队前的值`() {
        val deliveries = mutableListOf<Delivery>()
        val registry = ClientRegistry {}
        val dispatcher =
            VehicleFeedDispatcher(
                post = { action -> main.post(action) },
                isEnabled = { true },
                acceptValues = {},
                leases = { registry.all() },
            )
        val speed = Bundle().apply { putString("value", "42") }
        val first = Bundle().apply { putBundle("displaySpeed", speed) }
        try {
            val throwing =
                attach(registry, AutoContract.MAP, 10021) { bundle ->
                    deliveries += delivery("map", bundle)
                    throw IllegalStateException("first-feed")
                }
            val following =
                attach(registry, AutoContract.OBSERVER, 10022) { bundle ->
                    deliveries += delivery("observer", bundle)
                }
            throwing.feed = setOf("displaySpeed")
            throwing.feedIntervalMillis = 250
            following.feed = setOf("displaySpeed")
            following.feedIntervalMillis = 250

            dispatcher.publish(first, 1_000L)
            val selected = dispatcher.select(setOf("displaySpeed"))
            assertEquals(setOf("displaySpeed"), selected.keySet())
            assertFalse(selected.containsKey("at"))
            assertFalse(selected.containsKey("sequence"))
            assertTrue(dispatcher.select(setOf("gear")).isEmpty)
            assertNotSame(speed, selected.getBundle("displaySpeed"))
            selected.getBundle("displaySpeed")!!.putString("value", "edited")
            assertEquals("42", speed.getString("value"))

            val snap = dispatcher.snapshot()
            assertSame(speed, snap.getBundle("displaySpeed"))
            snap.remove("displaySpeed")
            snap.putString("injected", "x")
            assertNotNull(dispatcher.snapshot().getBundle("displaySpeed"))
            assertFalse(dispatcher.snapshot().containsKey("injected"))
            assertSame(speed, dispatcher.snapshot().getBundle("displaySpeed"))

            speed.putString("value", "99")
            assertEquals("99", dispatcher.snapshot().getBundle("displaySpeed")!!.getString("value"))
            assertEquals("edited", selected.getBundle("displaySpeed")!!.getString("value"))
            dispatcher.publish(values("7"), 2_000L)
            assertTrue(deliveries.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                listOf(
                    Delivery("map", "42", 1_000L, 1L),
                    Delivery("observer", "42", 1_000L, 1L),
                    Delivery("map", "7", 2_000L, 2L),
                    Delivery("observer", "7", 2_000L, 2L),
                ),
                deliveries,
            )
        } finally {
            registry.close()
            main.removeCallbacksAndMessages(null)
        }
    }

    private fun values(speed: String): Bundle =
        Bundle().apply {
            putBundle(
                "displaySpeed",
                Bundle().apply { putString("value", speed) },
            )
        }

    private fun attach(
        registry: ClientRegistry,
        role: String,
        uid: Int,
        onFeed: (Bundle) -> Unit,
    ): ClientRegistry.Lease = registry.attach(Binder(), uid, role, callback(onFeed))

    private fun callback(onFeed: (Bundle) -> Unit): IAutoCallback =
        object : IAutoCallback.Stub() {
            override fun onSnapshot(snapshot: Bundle) = Unit

            override fun onSurface(
                role: String,
                surface: Surface,
            ) = Unit

            override fun onProjectionState(
                role: String,
                state: String,
            ) = Unit

            override fun onVehicleFeed(values: Bundle) = onFeed(values)
        }

    private fun delivery(
        who: String,
        bundle: Bundle,
    ): Delivery =
        Delivery(
            who,
            speedOf(bundle),
            bundle.getLong("at"),
            bundle.getLong("sequence"),
        )

    private fun speedOf(bundle: Bundle): String? = bundle.getBundle("displaySpeed")?.getString("value")

    private data class Delivery(
        val who: String,
        val value: String?,
        val at: Long,
        val sequence: Long,
    )
}
