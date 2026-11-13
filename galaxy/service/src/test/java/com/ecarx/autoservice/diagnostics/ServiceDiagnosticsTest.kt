package com.ecarx.autoservice.diagnostics

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ecarx.autoservice.ipc.ClientRegistry
import com.ecarx.autoservice.ipc.ServiceSnapshots
import com.ecarx.autoservice.settings.ServiceFeatures
import com.ecarx.autoservice.vehicle.ServiceVehicleSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 调用生产 [ServiceDiagnostics.dump]。
 * 车辆口只记录被读到的诊断方法，不启动采集。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ServiceDiagnosticsTest {
    @Test
    fun `foundation检查先返回，普通文本保持前缀且只读缓存`() {
        val calls = mutableListOf<String>()
        val app = RuntimeEnvironment.getApplication()
        val registry = ClientRegistry {}
        val snapshots = ServiceSnapshots(post = { it.run() }, remove = {}, isClosed = { false })
        snapshots.publish(Bundle().apply { putString("status", "created") }, emptyList())
        var feedEnabled = false
        val vehicle =
            ServiceVehicleSession(
                context = app,
                clients = registry,
                main = Handler(Looper.getMainLooper()),
                post = { it.run() },
                isFeedEnabled = { feedEnabled },
                acceptValues = {},
                sentinelDemands = { emptyMap() },
                onPublish = {},
                rootLost = {},
                collectorFactory = {
                    object : ServiceVehicleSession.CollectorPort {
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
                        }

                        override fun setScan(enabled: Boolean) {
                            calls += "scan"
                        }

                        override fun scanning() = false

                        override fun stats(): String {
                            calls += "stats"
                            return "ticks=0"
                        }
                    }
                },
            )
        val diagnostics =
            ServiceDiagnostics(
                context = app,
                clients = registry,
                snapshots = snapshots,
                vehicle = {
                    calls += "vehicle"
                    vehicle
                },
                paused = { true },
                rootReady = { false },
                features = { ServiceFeatures() },
                foundation = {
                    calls += "foundation"
                    "{\"state\":\"stopped\"}"
                },
            )
        feedEnabled = true
        vehicle.deliverFeed(
            Bundle().apply {
                putBundle(
                    "displaySpeed",
                    Bundle().apply {
                        putString("value", "36")
                        putString("status", "VALID")
                        putLong("receivedAt", SystemClock.elapsedRealtime())
                        putString("unit", "km/h")
                        putInt("propertyId", 557874774)
                    },
                )
            },
            SystemClock.elapsedRealtime(),
        )
        shadowOf(Looper.getMainLooper()).idle()
        val early = StringWriter()
        diagnostics.dump(PrintWriter(early), arrayOf("--foundation-check", "--record-properties"))
        assertEquals("FOUNDATION_CHECK={\"state\":\"stopped\"}\n", early.toString().replace("\r\n", "\n"))
        assertEquals(listOf("foundation"), calls)
        calls.clear()
        val properties = StringWriter()
        diagnostics.dump(PrintWriter(properties), arrayOf("--record-properties", "--record-fields"))
        assertEquals("RECORD_PROPERTIES=[]\n", properties.toString().replace("\r\n", "\n"))
        assertEquals(listOf("vehicle"), calls)
        calls.clear()
        val fields = StringWriter()
        diagnostics.dump(PrintWriter(fields), arrayOf("--record-fields", "--cluster-fields"))
        val fieldText = fields.toString().replace("\r\n", "\n")
        assertTrue(fieldText.startsWith("RECORD_FIELDS="))
        assertTrue(fieldText.contains("\"displaySpeed\""))
        assertTrue(fieldText.contains("36"))
        assertEquals(listOf("vehicle"), calls)
        calls.clear()
        val cluster = StringWriter()
        diagnostics.dump(PrintWriter(cluster), arrayOf("--cluster-fields"))
        val clusterText = cluster.toString().replace("\r\n", "\n")
        assertTrue(clusterText.startsWith("CLUSTER_FIELDS="))
        assertTrue(clusterText.contains("\"displaySpeed\""))
        assertEquals(listOf("vehicle"), calls)
        val writer = StringWriter()
        diagnostics.dump(PrintWriter(writer), emptyArray())
        val text = writer.toString()
        assertTrue(text.startsWith("AUTOSERVICE_DIAGNOSTIC version="))
        assertTrue(text.contains("hab=archived"))
        assertTrue(text.contains("projectionArchived=true paused=true rootReady=false"))
        assertTrue(text.contains("vehicleStats=ticks=0"))
        assertTrue(calls.contains("stats"))
        assertTrue(calls.none { it == "scan" || it == "start" || it == "demand" || it == "close" })
        registry.close()
    }
}
