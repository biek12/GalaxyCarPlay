package com.galaxy.auto.client.ipc

import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一次性RPC、票据、FD与错误形状的真实行为用例。
 *
 * 结果回调只由记录型本地Stub回放；不接真实服务/车辆/Root/HTTP。缺服务与异常各自
 * 回执形状逐项断言，ticket类与仅closed类结果分开验证，不套用统一包装器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AutoClientRequestsTest {
    private fun service() = AutoClientConnectionTest.RecordingService()

    private fun subject(
        service: AutoClientConnectionTest.RecordingService,
        onState: (String) -> Unit = {},
    ): AutoClient {
        AutoClientConnectionTest.bind(service)
        return AutoClient(AutoClientConnectionTest.app(), AutoContract.OBSERVER, onState = onState)
    }

    private fun connect(
        subject: AutoClient,
        service: AutoClientConnectionTest.RecordingService,
    ) {
        subject.connect()
        AutoClientConnectionTest.await { service.names().contains("attach") }
    }

    private fun worker(): HandlerThread =
        Thread.getAllStackTraces().keys.filterIsInstance<HandlerThread>()
            .first { it.name == "AutoClient-${AutoContract.OBSERVER}" }

    @Test
    fun `一次性RPC的全部动作参数与默认实参保持`() {
        val service = service()
        val client = subject(service)
        val app = AutoClientConnectionTest.app()
        val clip = File(app.cacheDir, "a2-params-clip.bin").apply { writeText("CLIP") }
        try {
            connect(client, service)
            client.executeShell("adb", "cmd") {}
            client.reportSentinelIdle(99L)
            client.mediaCommand(7)
            client.setVehicleScan(true)
            client.vehiclePage(3) {}
            client.vehiclePage(4, true) {}
            client.vehicleValue(1, 2, 3) {}
            client.requestClientPermissions(listOf("camera")) {}
            client.configureSentinel(true) {}
            client.vehicleWebRequest("act") {}
            client.vehicleWebUpload("c1", "out", clip) {}
            client.prepareOemArtwork("OEM".toByteArray()) {}
            client.getVehicleValues(listOf("gear")) {}
            AutoClientConnectionTest.await { service.names().contains("prepareOemArtwork") }
            AutoClientConnectionTest.pumpAll()

            val shell = service.last("executeShell")
            assertEquals("adb", shell.args[1])
            assertEquals("cmd", shell.args[2])
            assertEquals(10000, shell.args[3])
            assertEquals(99L, service.last("reportSentinelIdle").args[1])
            assertEquals(7, service.last("mediaCommand").args[1])
            assertTrue(service.names().contains("setVehicleScan"))
            assertEquals(listOf(3, 32), service.last("getVehicleProperties").args.toList())
            assertEquals(listOf(4, 32), service.last("getVehicleCatalog").args.toList())
            assertEquals(listOf(1, 2, 3), service.last("getVehicleValue").args.toList())
            assertEquals(listOf("camera"), service.last("requestClientPermissions").args[1])
            assertEquals(true, service.last("configureSentinel").args[1])
            assertEquals("act", service.last("vehicleWebRequest").args[1])
            val upload = service.last("vehicleWebUpload")
            assertEquals("c1", upload.args[1])
            assertEquals("out", upload.args[2])
            assertEquals(clip.length(), upload.args[4])
            assertEquals(listOf("gear"), service.last("getVehicleValues").args[0])
        } finally {
            client.close()
            clip.delete()
        }
    }

    @Test
    fun `缺服务时权限哨兵Web与OEM各有自己的错误形状`() {
        val service = service()
        val client = subject(service)
        val replies = CopyOnWriteArrayList<Pair<String, Bundle>>()
        val app = AutoClientConnectionTest.app()
        val clip = File(app.cacheDir, "a2-no-service-clip.bin").apply { writeText("CLIP") }
        try {
            client.requestClientPermissions(listOf("camera")) { replies.add("permission" to it) }
            client.configureSentinel(true) { replies.add("sentinel" to it) }
            client.vehicleWebRequest("act") { replies.add("webRequest" to it) }
            client.vehicleWebUpload("c", "out", clip) { replies.add("upload" to it) }
            client.prepareOemArtwork("OEM".toByteArray()) { replies.add("oem" to it) }
            client.executeShell("adb", "cmd") { replies.add("shell" to it) }
            client.vehiclePage(0) { replies.add("page" to it) }
            client.vehicleValue(1, 2, 3) { replies.add("value" to it) }
            client.getVehicleValues(listOf("gear")) { replies.add("values" to it) }
            AutoClientConnectionTest.await { replies.size == 5 }
            AutoClientConnectionTest.pumpAll()
            assertEquals(5, replies.size)

            val byName = replies.toMap()
            assertFalse(byName.getValue("permission").getBoolean("granted"))
            assertEquals("IllegalStateException", byName.getValue("permission").getString("error"))
            assertFalse(byName.getValue("sentinel").getBoolean("accepted"))
            assertEquals("IllegalStateException", byName.getValue("sentinel").getString("error"))
            assertEquals("service_unavailable", byName.getValue("webRequest").getString("error"))
            assertEquals("媒体传输失败", byName.getValue("upload").getString("error"))
            assertEquals("IllegalStateException", byName.getValue("oem").getString("error"))
            assertTrue(clip.exists())
            assertTrue(
                app.cacheDir.listFiles { _, name -> name.startsWith("oem-artwork-") }.isNullOrEmpty(),
            )
        } finally {
            client.close()
            clip.delete()
        }
    }

    @Test
    fun `ticket类结果在旧代次与关闭之后都丢弃`() {
        val first = service()
        val firstClient = subject(first)
        val firstReplies = CopyOnWriteArrayList<Bundle>()
        try {
            connect(firstClient, first)
            firstClient.configureSentinel(true) { firstReplies.add(it) }
            AutoClientConnectionTest.await { first.names().contains("configureSentinel") }
            val stale = first.results.last()
            AutoClientConnectionTest.connection().onBindingDied(AutoClientConnectionTest.SERVICE_NAME)
            AutoClientConnectionTest.pumpAll()
            stale.onResult(Bundle().apply { putBoolean("accepted", true) })
            AutoClientConnectionTest.pumpAll()
            assertTrue(firstReplies.isEmpty())
        } finally {
            firstClient.close()
        }

        val second = service()
        val secondClient = subject(second)
        val secondReplies = CopyOnWriteArrayList<Bundle>()
        try {
            connect(secondClient, second)
            secondClient.executeShell("adb", "cmd") { secondReplies.add(it) }
            AutoClientConnectionTest.await { second.names().contains("executeShell") }
            val result = second.results.last()
            result.onResult(Bundle())
            AutoClientConnectionTest.await { secondReplies.size == 1 }
            secondClient.close()
            result.onResult(Bundle())
            AutoClientConnectionTest.pumpAll()
            assertEquals(1, secondReplies.size)
        } finally {
            secondClient.close()
        }
    }

    @Test
    fun `page与value与getValues在代次推进后仍只按closed回执`() {
        val service = service()
        val client = subject(service)
        val replies = CopyOnWriteArrayList<String>()
        try {
            connect(client, service)
            client.vehiclePage(0) { replies.add("page") }
            client.vehicleValue(1, 2, 3) { replies.add("value") }
            client.getVehicleValues(listOf("gear")) { replies.add("values") }
            // 只推进worker，让三个结果停留在main队列；随后断线使generation前进。
            shadowOf(worker().looper).idle()
            AutoClientConnectionTest.connection().onServiceDisconnected(AutoClientConnectionTest.SERVICE_NAME)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3, replies.size)
            assertTrue(replies.containsAll(listOf("page", "value", "values")))
        } finally {
            client.close()
        }
    }

    @Test
    fun `FD调用期间可读返回后关闭临时文件删除而用户文件保留`() {
        val service = service()
        val client = subject(service)
        val app = AutoClientConnectionTest.app()
        val clip = File(app.cacheDir, "a2-fd-clip.bin").apply { writeText("CLIP-DATA") }
        try {
            connect(client, service)
            client.vehicleWebUpload("c1", "out", clip) {}
            AutoClientConnectionTest.await { service.names().contains("vehicleWebUpload") }
            assertEquals("CLIP-DATA", service.fdText.last())
            assertTrue(clip.exists())
            assertFalse(service.heldFd.last().fileDescriptor.valid())

            client.prepareOemArtwork("OEM-DATA".toByteArray()) {}
            AutoClientConnectionTest.await { service.names().contains("prepareOemArtwork") }
            assertEquals("OEM-DATA", service.fdText.last())
            assertFalse(service.heldFd.last().fileDescriptor.valid())
            assertTrue(
                app.cacheDir.listFiles { _, name -> name.startsWith("oem-artwork-") }.isNullOrEmpty(),
            )
        } finally {
            client.close()
            clip.delete()
        }
    }

    @Test
    fun `上传异常固定中文错误且Shell正文不进入状态`() {
        val states = CopyOnWriteArrayList<String>()
        val service = service()
        val client = subject(service, onState = { states.add(it) })
        val replies = CopyOnWriteArrayList<Bundle>()
        try {
            connect(client, service)
            service.throwOn = "executeShell"
            client.executeShell("adb", "SHELL-SECRET-BODY") {}
            AutoClientConnectionTest.await { states.isNotEmpty() }
            assertEquals("ipc:IllegalStateException", states.single())
            assertFalse(states.any { it.contains("SHELL-SECRET-BODY") })
            service.throwOn = null

            client.vehicleWebUpload("c", "out", File(AutoClientConnectionTest.app().cacheDir, "missing.bin")) {
                replies.add(it)
            }
            AutoClientConnectionTest.await { replies.isNotEmpty() }
            assertEquals("媒体传输失败", replies.single().getString("error"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `连接握手不自动开启诊断扫描`() {
        val service = service()
        val client = subject(service)
        try {
            connect(client, service)
            assertFalse(service.names().contains("setVehicleScan"))
            client.setVehicleScan(true)
            AutoClientConnectionTest.await { service.names().contains("setVehicleScan") }
            assertEquals(1, service.names().count { it == "setVehicleScan" })
        } finally {
            client.close()
        }
    }
}
