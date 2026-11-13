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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 用生产 [ServiceVehicleSession]、生产 [ClientRegistry] 和生产 [VehicleDemand.merge] 核对订阅合并。
 * 采集器只记录真实端口调用，不另写需求状态机，也不读车机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ServiceVehicleSessionTest {
    @Test
    fun `订阅并集翻译别名并取最紧间隔，哨兵按500毫秒，无人订阅时需求为空`() {
        val recorded = RecordingCollector()
        val registry = ClientRegistry {}
        var demands = emptyMap<String, Int>()
        val session = session(registry, recorded) { demands }
        try {
            session.recalculateDemand()
            assertEquals(emptyMap<String, Int>(), recorded.lastDemand)
            assertEquals(listOf("demand"), recorded.calls)

            val mapLease = attach(registry, AutoContract.MAP, 10)
            val observer = attach(registry, AutoContract.OBSERVER, 11)
            demands =
                mapOf(
                    "sentinelMode" to 1,
                    "centralLock" to 1,
                    "gear" to 1,
                    "soc" to 1,
                )
            session.setFeed(mapLease, arrayOf("displaySpeed", "fuelConsumption"), 1_000, 100L)
            session.setFeed(observer, arrayOf("displaySpeed"), 250, 200L)
            assertEquals(
                mapOf(
                    "displaySpeed" to 250,
                    "property.10061" to 1_000,
                    "sentinelMode" to 500,
                    "centralLock" to 500,
                    "gear" to 500,
                    "soc" to 500,
                ),
                recorded.lastDemand,
            )
            assertFalse(recorded.calls.contains("start"))
            assertFalse(recorded.calls.any { it.startsWith("scan") })
        } finally {
            registry.close()
        }
    }

    @Test
    fun `停服和换车型保留意图并清空缓存，重启之后才写入需求`() {
        val recorded = RecordingCollector()
        val registry = ClientRegistry {}
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("platform", 0)
            .edit()
            .putString("model", "L6")
            .commit()
        val session = session(registry, recorded) { emptyMap() }
        try {
            val lease = attach(registry, AutoContract.MAP, 10)
            session.deliverFeed(
                Bundle().apply {
                    putBundle("displaySpeed", Bundle().apply { putString("value", "12") })
                },
                10L,
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                "12",
                session
                    .values(arrayOf("displaySpeed"))
                    .getBundle("displaySpeed")
                    ?.getString("value"),
            )
            assertTrue(recorded.calls.isEmpty())

            lease.feed = setOf("displaySpeed", "not-in-catalog")
            lease.feedIntervalMillis = 1_000
            lease.lastFeedAt = 40L
            session.retainFeeds()
            session.stop()
            session.clear()
            session.markStopped()
            assertEquals(setOf("displaySpeed", "not-in-catalog"), lease.feed)
            assertEquals(0L, lease.lastFeedAt)
            assertEquals("stopped", session.discovery)
            assertEquals(0, session.discoveredCount)
            assertTrue(session.values(arrayOf("displaySpeed")).isEmpty)
            assertEquals(listOf("stop"), recorded.calls)

            session.start()
            session.recalculateDemand()
            assertEquals(listOf("stop", "start", "demand"), recorded.calls)
            assertEquals(mapOf("displaySpeed" to 1_000, "not-in-catalog" to 1_000), recorded.lastDemand)

            context
                .getSharedPreferences("platform", 0)
                .edit()
                .putString("model", "L7")
                .commit()
            recorded.calls.clear()
            session.retainFeeds(session.keys().toSet())
            session.clear()
            session.stop()
            assertEquals(setOf("displaySpeed"), lease.feed)
            assertEquals(0L, lease.lastFeedAt)
            assertEquals(listOf("stop"), recorded.calls)
            session.start()
            session.recalculateDemand()
            assertEquals(listOf("stop", "start", "demand"), recorded.calls)
            assertEquals(mapOf("displaySpeed" to 1_000), recorded.lastDemand)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `扫描只委托采集端口，缓存读取不触发采集`() {
        val recorded = RecordingCollector()
        val registry = ClientRegistry {}
        val session = session(registry, recorded) { emptyMap() }
        try {
            session.setScan(true)
            session.setScan(false)
            assertTrue(session.values(arrayOf("displaySpeed")).isEmpty)
            assertTrue(session.values(arrayOf("missing-key")).isEmpty)
            assertEquals(listOf("scan:true", "scan:false"), recorded.calls)
        } finally {
            registry.close()
        }
    }

    private fun session(
        registry: ClientRegistry,
        recorded: RecordingCollector,
        demands: () -> Map<String, Int>,
    ): ServiceVehicleSession {
        val main = Handler(Looper.getMainLooper())
        return ServiceVehicleSession(
            context = RuntimeEnvironment.getApplication(),
            clients = registry,
            main = main,
            post = { action -> main.post(action) },
            isFeedEnabled = { true },
            acceptValues = {},
            sentinelDemands = demands,
            onPublish = {},
            rootLost = {},
            collectorFactory = { recorded },
        )
    }

    private fun attach(
        registry: ClientRegistry,
        role: String,
        uid: Int,
    ): ClientRegistry.Lease =
        registry.attach(
            Binder(),
            uid,
            role,
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

                override fun onVehicleFeed(values: Bundle) = Unit
            },
        )

    /** 只记录 [ServiceVehicleSession.CollectorPort] 的真实调用和最后一次需求。 */
    private class RecordingCollector : ServiceVehicleSession.CollectorPort {
        val calls = mutableListOf<String>()
        var lastDemand: Map<String, Int> = emptyMap()

        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun close() {
            calls += "close"
        }

        override fun setDemand(next: Map<String, Int>) {
            calls += "demand"
            lastDemand = next.toMap()
        }

        override fun setScan(enabled: Boolean) {
            calls += "scan:$enabled"
        }

        override fun scanning(): Boolean = calls.lastOrNull() == "scan:true"

        override fun stats(): String = "recorded"
    }
}
