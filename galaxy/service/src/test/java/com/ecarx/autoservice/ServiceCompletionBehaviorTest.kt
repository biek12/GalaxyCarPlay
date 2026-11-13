package com.ecarx.autoservice

import android.app.NotificationManager
import android.content.Intent
import com.galaxy.auto.contract.AutoContract
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
            assertEquals("AutoService", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertEquals("车辆数据与仪表投射", notification.extras.getString(android.app.Notification.EXTRA_TEXT))
            val writer = StringWriter()
            dump(service, writer, emptyArray())
            val text = writer.toString()
            assertTrue(text.contains("AUTOSERVICE_DIAGNOSTIC version=${AutoContract.VERSION}"))
            assertTrue(text.contains("hab=archived"))
            assertTrue(text.contains("projectionArchived=true"))
            controller.destroy()
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
}
