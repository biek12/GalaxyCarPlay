package com.ecarx.autoservice.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.ecarx.autoservice.DiagnosticActivity
import com.ecarx.autoservice.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executor

/**
 * 诊断页用例：真实 `DiagnosticActivity` + 真实 XML/binding/Controller，
 * 仅替换 AutoClient 边界与 IO 执行器。
 *
 * 覆盖 DG-01（准备/Shell/扫描/分页）与 DG-02（日志读取、导入流关闭、销毁后不触旧 View）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DiagnosticPageBehaviorTest {
    private lateinit var session: RecordingSession
    private lateinit var io: ManualExecutor
    private lateinit var controller: ActivityController<DiagnosticActivity>
    private lateinit var activity: DiagnosticActivity

    /** 记录调用并允许测试驱动生产回调的会话替身。 */
    private class RecordingSession : ServicePageSession {
        var connects = 0
        var closes = 0
        var onSnapshot: ((Bundle) -> Unit)? = null
        var onState: ((String) -> Unit)? = null
        val shells = mutableListOf<ShellCall>()
        var scans = 0
        val pages = mutableListOf<Pair<Int, (Bundle) -> Unit>>()

        override fun connect() {
            connects++
        }

        override fun close() {
            closes++
        }

        override fun executeShell(transport: String, command: String, result: (Bundle) -> Unit) {
            shells += ShellCall(transport, command, result)
        }

        override fun setVehicleScan(enabled: Boolean) {
            scans++
        }

        override fun vehiclePage(offset: Int, result: (Bundle) -> Unit) {
            pages += offset to result
        }

        /** 模拟服务快照回调。 */
        fun push(value: Bundle) = onSnapshot?.invoke(value) ?: Unit
    }

    private data class ShellCall(
        val transport: String,
        val command: String,
        val result: (Bundle) -> Unit,
    )

    /** 手动推进的单线程替身；不是 ExecutorService，避免被 destroy 关闭。 */
    private class ManualExecutor : Executor {
        private val tasks = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        /** 依次执行当前排队任务。 */
        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).run()
        }
    }

    /** 记录是否被关闭的输入流，用于验证导入不泄漏文件句柄。 */
    private class TrackingStream(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    @Before
    fun setUp() {
        RuntimeEnvironment
            .getApplication()
            .getSharedPreferences("platform", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        session = RecordingSession()
        io = ManualExecutor()
        controller = Robolectric.buildActivity(DiagnosticActivity::class.java)
        activity = controller.get()
        activity.sessionFactory = { _, onSnapshot, onState ->
            session.onSnapshot = onSnapshot
            session.onState = onState
            session
        }
        activity.ioExecutor = io
        controller.create().start().resume()
    }

    @After
    fun tearDown() {
        if (!activity.isDestroyed) controller.pause().stop().destroy()
    }

    /** 结果区文本。 */
    private fun result(): String = activity.findViewById<TextView>(R.id.diagnostic_result).text.toString()

    /** Shell 输入框。 */
    private fun input(): EditText = activity.findViewById(R.id.diagnostic_shell_input)

    /** 清空并返回本次累积的服务 Intent。 */
    private fun drain(): List<Intent> = generateSequence { shadowOf(activity).nextStartedService }.toList()

    /** 切换自动运行偏好。 */
    private fun automatic(enabled: Boolean) {
        activity
            .getSharedPreferences("platform", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("automatic", enabled)
            .commit()
    }

    @Test
    fun prepareRequiresEnabledBackgroundAndOtherwiseSendsNothing() {
        automatic(false)
        activity.findViewById<View>(R.id.diagnostic_prepare).performClick()
        assertEquals(activity.getString(R.string.diagnostic_paused), result())
        assertTrue(drain().isEmpty())

        automatic(true)
        activity.findViewById<View>(R.id.diagnostic_prepare).performClick()
        assertEquals("prepare", drain().single().action)
    }

    @Test
    fun shellRunsThroughProductionSessionAndShowsRealResult() {
        input().setText("id")
        activity.findViewById<View>(R.id.diagnostic_run_root).performClick()
        assertEquals(1, session.shells.size)
        assertEquals("ROOT", session.shells.single().transport)
        assertEquals("id", session.shells.single().command)
        session.shells.single().result(
            Bundle().apply {
                putInt("exitCode", 0)
                putBoolean("timedOut", false)
                putString("error", "")
                putString("output", "uid=0(root)")
            },
        )
        assertTrue(result().contains("exit=0"))
        assertTrue(result().contains("timeout=false"))
        assertTrue(result().contains("uid=0(root)"))
    }

    @Test
    fun disabledFeatureBlocksShellWithResourceFeedback() {
        activity
            .getSharedPreferences("platform", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("rootEnabled", false)
            .commit()
        input().setText("id")
        activity.findViewById<View>(R.id.diagnostic_run_root).performClick()
        assertTrue(session.shells.isEmpty())
        assertTrue(result().contains(activity.getString(R.string.service_feature_root)))
    }

    @Test
    fun scanRequestsOnceAndSnapshotOnlyBackfills() {
        activity.findViewById<View>(R.id.diagnostic_scan).performClick()
        assertEquals(1, session.scans)
        assertEquals(activity.getString(R.string.diagnostic_scan_on), buttonText(R.id.diagnostic_scan))
        assertEquals(activity.getString(R.string.diagnostic_scan_started), result())

        session.push(Bundle().apply { putBoolean("vehicleScanning", true) })
        assertEquals("快照回填不得再次请求", 1, session.scans)

        session.push(Bundle().apply { putBoolean("vehicleScanning", false) })
        assertEquals(activity.getString(R.string.diagnostic_scan_off), buttonText(R.id.diagnostic_scan))
        assertEquals(1, session.scans)
    }

    @Test
    fun vehiclePageAdvancesByActualItemsAndWrapsAtEnd() {
        activity.findViewById<View>(R.id.diagnostic_vehicle_page).performClick()
        assertEquals(0, session.pages.single().first)
        session.pages.single().second(
            Bundle().apply {
                putInt("total", 4)
                putParcelableArrayList(
                    "items",
                    arrayListOf(
                        Bundle().apply {
                            putString("name", "speed")
                            putInt("propertyId", 1)
                            putInt("areaId", 0)
                            putString("status", "ok")
                            putString("value", "0")
                        },
                        Bundle().apply {
                            putString("name", "gear")
                            putInt("propertyId", 2)
                            putInt("areaId", 0)
                            putString("status", "ok")
                            putString("value", "P")
                        },
                    ),
                )
            },
        )
        assertTrue(result().contains(activity.getString(R.string.diagnostic_vehicle_header, 0, 4)))
        assertTrue(result().contains("speed"))

        activity.findViewById<View>(R.id.diagnostic_vehicle_page).performClick()
        assertEquals(2, session.pages.last().first)
    }

    @Test
    fun logsAreReadOnWorkerAndShownFromRealFiles() {
        val dir = File(activity.filesDir, "diagnostics").apply { mkdirs() }
        File(dir, "app.log").writeText("合成日志 A")
        File(dir, "root.log").writeText("合成日志 B")
        activity.findViewById<View>(R.id.diagnostic_logs).performClick()
        assertEquals("读取任务先排队，未执行前不改结果", activity.getString(R.string.diagnostic_waiting), result())
        io.runAll()
        assertTrue(result().contains("app.log"))
        assertTrue(result().contains("合成日志 A"))
        assertTrue(result().contains("合成日志 B"))
    }

    @Test
    fun destroyedPageDropsLateIoResult() {
        activity.findViewById<View>(R.id.diagnostic_logs).performClick()
        val before = result()
        controller.pause().stop().destroy()
        io.runAll()
        assertEquals("销毁后不得更新旧 View", before, result())
        assertEquals("prepare 不能由迟到结果触发", 0, drain().count { it.action == "prepare" })
    }

    @Test
    fun documentImportClosesStreamAndPreparesOnSuccess() {
        val uri = Uri.parse("content://galaxy.test/qnx")
        val stream = TrackingStream("user\npass".toByteArray())
        shadowOf(activity.contentResolver).registerInputStream(uri, stream)
        activity.deliverDocumentResult(
            71,
            Activity.RESULT_OK,
            Intent().setData(uri),
        )
        io.runAll()
        assertTrue("导入流必须关闭", stream.closed)
        assertEquals(activity.getString(R.string.diagnostic_auth_saved), result())
        assertEquals("prepare", drain().single().action)
    }

    @Test
    fun modelSelectionWritesPreferenceAndNotifiesService() {
        activity.findViewById<View>(R.id.diagnostic_model_l7).performClick()
        assertEquals(
            "L7",
            activity.getSharedPreferences("platform", Context.MODE_PRIVATE).getString("model", null),
        )
        assertEquals("model", drain().single().action)
    }

    @Test
    fun leavingPageClosesObserverWithoutStoppingService() {
        controller.pause().stop()
        assertEquals(1, session.closes)
        assertTrue("离开页面不发任何服务命令", drain().isEmpty())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.diagnostic_result).visibility)
    }

    /** 读取按钮文案。 */
    private fun buttonText(id: Int): String = activity.findViewById<TextView>(id).text.toString()
}
