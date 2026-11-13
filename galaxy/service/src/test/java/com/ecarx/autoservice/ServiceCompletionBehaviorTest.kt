package com.ecarx.autoservice

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.view.Surface
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoCallback
import com.galaxy.auto.contract.IAutoResult
import com.galaxy.auto.contract.IAutoService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 用 Robolectric 跑生产 [AutoService] 的创建、绑定、快照、诊断和销毁。
 * 自动运行和诊断上传都关闭，且没有登记材料，避免拉起 Root、车辆采集和 HTTP。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ServiceCompletionBehaviorTest {
    @Test
    fun `创建绑定后快照和诊断保持归档态，通知使用原通道和编号`() {
        val app = RuntimeEnvironment.getApplication()
        app
            .getSharedPreferences("platform", 0)
            .edit()
            .putBoolean("automatic", false)
            .commit()
        app
            .getSharedPreferences("diagnostics", 0)
            .edit()
            .putBoolean("uploadEnabled", false)
            .commit()
        assertFalse(File(app.noBackupFilesDir, "remote-enrollment").isFile)
        val controller = Robolectric.buildService(AutoService::class.java).create()
        val service = controller.get()
        try {
            val bound = service.onBind(Intent(AutoContract.ACTION))
            assertNotNull(bound)
            assertNull(service.onBind(Intent("other")))
            val snapshot = IAutoService.Stub.asInterface(bound).snapshot
            assertEquals(AutoContract.VERSION, snapshot.getInt("version"))
            assertFalse(snapshot.getBoolean("qnxReady"))
            assertFalse(snapshot.getBoolean("projectionRequested"))
            assertFalse(snapshot.containsKey("vehicle"))
            assertEquals("content://com.ecarx.autoservice.artwork/current", snapshot.getString("artworkUri"))
            val manager = service.getSystemService(NotificationManager::class.java)
            val shadow = shadowOf(manager)
            val notification = shadow.getNotification(71)
            assertNotNull(notification)
            assertEquals("platform", notification.channelId)
            assertEquals("平台与仪表服务", manager.getNotificationChannel("platform").name.toString())
            assertEquals("AutoService", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("车辆数据与仪表投射", notification.extras.getString(Notification.EXTRA_TEXT))
            val writer = StringWriter()
            dump(service, writer, emptyArray())
            val text = writer.toString()
            assertTrue(text.contains("AUTOSERVICE_DIAGNOSTIC version=${AutoContract.VERSION}"))
            assertTrue(text.contains("hab=archived"))
            assertTrue(text.contains("projectionArchived=true"))
            ShadowBinder.setCallingUid(Process.myUid())
            val api = IAutoService.Stub.asInterface(bound)
            val owner = Binder()
            api.attach(owner, AutoContract.OBSERVER, callback())
            controller.destroy()
            shadowOf(Looper.getMainLooper()).idle()
            val closedDump = StringWriter()
            dump(service, closedDump, emptyArray())
            assertTrue(closedDump.toString().contains("clients="))
            assertFalse(closedDump.toString().contains("observer:"))
            api.attach(owner, AutoContract.OBSERVER, callback())
            val returned = arrayOfNulls<Bundle>(1)
            api.vehicleWebRequest(
                owner,
                "state",
                Bundle(),
                object : IAutoResult.Stub() {
                    override fun onResult(value: Bundle) {
                        returned[0] = value
                    }
                },
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(returned[0])
            val after = StringWriter()
            dump(service, after, arrayOf("--foundation-check"))
            assertTrue(after.toString().startsWith("FOUNDATION_CHECK="))
        } finally {
            controller.destroy()
        }
    }

    private fun dump(
        service: AutoService,
        writer: StringWriter,
        args: Array<String>,
    ) {
        val method =
            AutoService::class.java.getDeclaredMethod(
                "dump",
                FileDescriptor::class.java,
                PrintWriter::class.java,
                Array<String>::class.java,
            )
        method.isAccessible = true
        method.invoke(service, FileDescriptor.out, PrintWriter(writer), args)
    }

    private fun callback(): IAutoCallback =
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
        }
}
