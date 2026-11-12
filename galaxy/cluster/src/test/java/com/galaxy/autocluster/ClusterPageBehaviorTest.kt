package com.galaxy.autocluster

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.widget.TextView
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.ui.GalaxySettingRow
import com.galaxy.autocluster.runtime.ClusterDisplayStore
import com.galaxy.autocluster.ui.InstrumentMusicPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.FileOutputStream
import com.galaxy.auto.ui.R as UiR

/**
 * C1 新增标准用例：页面生命周期与显式恢复（C1-05）、外观选择（C1-06）、真实图片导入（C1-07），
 * 以及 C1-02 的扩展回执（automatic/断连/图片准备/projection 门控）。
 *
 * 全部驱动真实 ClusterActivity/XML/Presenter/Store/导入；AutoClient 只在 sessionFactory
 * 处替换为记录型替身，导入调度只替换边界，IO 代码为生产实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w571dp-h1028dp-280dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterPageBehaviorTest {
    // ---------- C1-02 扩展：正常回执的含义与门控 ----------

    @Test
    fun statusReceiptsKeepOriginalMeaningAndDoNotStartServices() {
        ClusterPageFixture.declareServiceUnbindable()
        val app = RuntimeEnvironment.getApplication()
        val store = ClusterDisplayStore(app)
        store.submitElements(ClusterDisplayConfiguration(mode = ClusterDisplayConfiguration.OEM_ELEMENTS, music = true))
        val controller = Robolectric.buildActivity(ClusterActivity::class.java)
        val session = ClusterPageFixture.install(controller.get())
        controller.setup()
        try {
            val activity = controller.get()
            ClusterPageFixture.row(activity.window.decorView, "原厂增强").performClick()
            val status = activity.findViewById<TextView>(R.id.oem_status)
            val bundle =
                Bundle().apply {
                    putBundle(
                        "features",
                        Bundle().apply {
                            putBoolean("rootEnabled", true)
                            putBoolean("projection", false)
                        },
                    )
                    putBundle("clusterElements", store.elements().bundle())
                }
            session.push(bundle)
            // projection=false 而 rootEnabled=true 不禁止原厂增强。
            assertEquals("配置已保存，等待仪表确认", status.text.toString())
            bundle.putBoolean("automatic", false)
            session.push(bundle)
            assertEquals("AutoService 已暂停，显示选择已保留", status.text.toString())
            bundle.remove("automatic")
            bundle.putString("clusterConfigurationStatus", "pending")
            session.push(bundle)
            assertEquals("正在准备背景图片", status.text.toString())
            bundle.putString("clusterConfigurationStatus", "failed")
            session.push(bundle)
            assertEquals("背景图片未能应用，显示选择已保留", status.text.toString())
            session.pushState("service_disconnected")
            assertEquals("等待服务连接", status.text.toString())
            // 全部回执快照不产生 apply/stop。
            val commands =
                generateSequence { shadowOf(app).nextStartedService }
                    .toList()
                    .filter { it.component?.className == ClusterRenderService::class.java.name }
            assertTrue(commands.none { it.action == "apply" || it.action == "stop" })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // ---------- C1-05：页面生命周期与显式恢复 ----------

    @Test
    fun pageLifecycleKeepsSingleObserverAndExplicitRestoreRunsOnce() {
        ClusterPageFixture.declareServiceUnbindable()
        val app = RuntimeEnvironment.getApplication()
        // Fixture A：enabled=false 启动，切音乐页投递两次快照。
        val controller = Robolectric.buildActivity(ClusterActivity::class.java)
        val session = ClusterPageFixture.install(controller.get())
        controller.setup()
        val root = controller.get().window.decorView
        ClusterPageFixture.row(root, "原厂增强").performClick()
        ClusterPageFixture.row(root, "音乐卡片").performClick()
        assertEquals(1, session.connectCount)
        val previewBefore = ClusterPageFixture.walk(root).filterIsInstance<InstrumentMusicPreview>().single()
        session.push(Bundle().apply { putString("clusterElementStatus", "waiting-content") })
        session.push(Bundle().apply { putString("clusterElementStatus", "active") })
        assertEquals(1, ClusterPageFixture.walk(root).filterIsInstance<InstrumentMusicPreview>().count())
        assertSame(previewBefore, ClusterPageFixture.walk(root).filterIsInstance<InstrumentMusicPreview>().single())

        fun commands(): List<Intent> =
            generateSequence { shadowOf(app).nextStartedService }
                .toList()
                .filter { it.component?.className == ClusterRenderService::class.java.name }
        assertTrue(commands().none { it.action == "apply" || it.action == "stop" })
        // 配置变化：重建壳与当前页，预览按新 Context 重建一次，路由保留。
        controller.configurationChange(Configuration())
        val rebound = controller.get().window.decorView
        assertEquals("音乐卡片", rebound.findViewById<TextView>(R.id.cluster_header_title).text.toString())
        assertEquals(1, ClusterPageFixture.walk(rebound).filterIsInstance<InstrumentMusicPreview>().count())
        assertNotSame(
            previewBefore,
            ClusterPageFixture.walk(rebound).filterIsInstance<InstrumentMusicPreview>().single(),
        )
        assertTrue(commands().none { it.action == "apply" || it.action == "stop" })
        // 保存并重建：路由与选图状态经旧键恢复。
        val state = Bundle()
        controller.saveInstanceState(state)
        assertEquals("music", state.getString("page"))
        controller.pause().stop().destroy()
        assertEquals(1, session.closeCount)
        // 销毁后的迟到快照：不再发命令、不触旧页面。
        session.push(Bundle().apply { putString("clusterElementStatus", "active") })
        assertTrue(commands().none { it.action == "apply" || it.action == "stop" })
        // Fixture B：enabled=true 启动，恢复输出恰好一次。
        val store = ClusterDisplayStore(app)
        store.submitElements(ClusterDisplayConfiguration(mode = ClusterDisplayConfiguration.OEM_ELEMENTS, music = true))
        val controllerB = Robolectric.buildActivity(ClusterActivity::class.java)
        val sessionB = ClusterPageFixture.install(controllerB.get())
        controllerB.setup()
        val rootB = controllerB.get().window.decorView
        assertEquals(1, commands().count { it.action == null })
        // 取消恢复：不写配置不发命令。
        ClusterPageFixture.button(rootB, "恢复原厂显示").performClick()
        ClusterPageFixture.button(rootB, "取消").performClick()
        assertTrue(store.enabled)
        assertTrue(commands().none { it.action == "stop" })
        // 确认恢复：enabled=false、stop 恰好一次；结果页先等待、收到 restored 才确认。
        ClusterPageFixture.button(rootB, "恢复原厂显示").performClick()
        ClusterPageFixture.button(rootB, "恢复原厂").performClick()
        assertFalse(store.enabled)
        assertEquals(1, commands().count { it.action == "stop" })
        val resultStatus = controllerB.get().findViewById<TextView>(R.id.result_status)
        assertEquals("已撤销本 App 显示需求，等待仪表恢复确认。", resultStatus.text.toString())
        sessionB.push(Bundle().apply { putString("clusterElementStatus", "restored") })
        assertEquals("已确认恢复原厂显示", resultStatus.text.toString())
        controllerB.pause().stop().destroy()
        assertEquals(1, sessionB.connectCount)
        assertEquals(1, sessionB.closeCount)
        sessionB.push(Bundle().apply { putString("clusterElementStatus", "active") })
        assertTrue(commands().none { it.action == "apply" || it.action == "stop" })
    }

    // ---------- C1-06：外观选择 ----------

    @Test
    fun appearanceSelectionPersistsAndReloadsOnlyWhenEnabled() {
        ClusterPageFixture.declareServiceUnbindable()
        val app = RuntimeEnvironment.getApplication()
        val preferences = app.getSharedPreferences("cluster-display", 0)

        fun reloadCommands(): List<Intent> =
            generateSequence { shadowOf(app).nextStartedService }
                .toList()
                .filter { it.component?.className == ClusterRenderService::class.java.name && it.action == "reload" }

        // enabled=false：取消不写；确认 day 不重载。
        val controller = Robolectric.buildActivity(ClusterActivity::class.java)
        ClusterPageFixture.install(controller.get())
        controller.setup()
        ClusterPageFixture.button(controller.get().window.decorView, "设置").performClick()
        ClusterPageFixture.row(controller.get().window.decorView, "日夜外观").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val cancelled = ShadowDialog.getLatestDialog() as AlertDialog
        cancelled.cancel()
        assertFalse(preferences.contains("appearance"))
        assertTrue(reloadCommands().isEmpty())
        ClusterPageFixture.row(controller.get().window.decorView, "日夜外观").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val confirmed = ShadowDialog.getLatestDialog() as AlertDialog
        confirmed.listView.performItemClick(null, 1, 1)
        confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("day", preferences.getString("appearance", null))
        assertTrue(reloadCommands().isEmpty())
        controller.pause().stop().destroy()

        // enabled=true：确认 night 只发一次 reload，且不改背景/音乐。
        val store = ClusterDisplayStore(app)
        val before = ClusterDisplayConfiguration(mode = ClusterDisplayConfiguration.OEM_ELEMENTS, music = true)
        store.submitElements(before)
        val controllerEnabled = Robolectric.buildActivity(ClusterActivity::class.java)
        ClusterPageFixture.install(controllerEnabled.get())
        controllerEnabled.setup()
        drainServices()
        ClusterPageFixture.button(controllerEnabled.get().window.decorView, "设置").performClick()
        ClusterPageFixture.row(controllerEnabled.get().window.decorView, "日夜外观").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val nightDialog = ShadowDialog.getLatestDialog() as AlertDialog
        nightDialog.listView.performItemClick(null, 2, 2)
        nightDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("night", preferences.getString("appearance", null))
        assertEquals(1, reloadCommands().size)
        assertEquals(before, store.read())
        controllerEnabled.pause().stop().destroy()

        // 重建读取原 prefs；包装 Context 后的实际控件引用公共色。
        val controllerRead = Robolectric.buildActivity(ClusterActivity::class.java)
        ClusterPageFixture.install(controllerRead.get())
        controllerRead.setup()
        val readRoot = controllerRead.get().window.decorView
        ClusterPageFixture.button(readRoot, "设置").performClick()
        assertEquals(
            "夜间",
            controllerRead
                .get()
                .findViewById<GalaxySettingRow>(R.id.settings_appearance_row)
                .valueView.text
                .toString(),
        )
        assertEquals(
            0xE5FFFFFF.toInt(),
            controllerRead.get().resources.getColor(UiR.color.galaxy_ui_text),
        )
        // 跟随系统 auto 选项同样可保存。
        ClusterPageFixture.row(readRoot, "日夜外观").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val autoDialog = ShadowDialog.getLatestDialog() as AlertDialog
        autoDialog.listView.performItemClick(null, 0, 0)
        autoDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("auto", preferences.getString("appearance", null))
        controllerRead.pause().stop().destroy()
    }

    // ---------- C1-07：真实图片导入 ----------

    @Test
    fun imageImportSavesOriginalBytesAndIgnoresInvalidOrLateResults() {
        ClusterPageFixture.declareServiceUnbindable()
        val app = RuntimeEnvironment.getApplication()
        val store = ClusterDisplayStore(app)
        // 预置启用配置：导入成功必须保留当下 music 并恰好一次 apply。
        val initial = ClusterDisplayConfiguration(mode = ClusterDisplayConfiguration.OEM_ELEMENTS, music = true)
        store.submitElements(initial)
        val sourceDir = File(app.cacheDir, "c1-import-source").apply { mkdirs() }
        val source = writeRealPng(File(sourceDir, "source.png"), 0xFF00FF00)
        var savedName = ""
        val controller = Robolectric.buildActivity(ClusterActivity::class.java)
        val session = ClusterPageFixture.install(controller.get())
        controller.setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView

            fun drain(): List<Intent> = generateSequence { shadowOf(app).nextStartedService }.toList()
            drain()
            ClusterPageFixture.row(root, "原厂增强").performClick()
            ClusterPageFixture.row(root, "背景").performClick()
            // 通过现有 Picker result 合同交生产导入；等待 worker + 主线程完成。
            ClusterPageFixture.button(root, "选择新图片").performClick()
            activity.deliverActivityResult(41, Activity.RESULT_OK, Intent().putExtra("imagePath", source.absolutePath))
            shadowOf(Looper.getMainLooper()).idle()
            val directory = File(app.filesDir, "display-backgrounds")
            val saved = directory.listFiles()!!.single()
            savedName = saved.name
            assertTrue(saved.name.startsWith("background-") && saved.name.endsWith(".png"))
            assertTrue(saved.readBytes().contentEquals(source.readBytes()))
            assertEquals(saved.name, store.read().image)
            assertEquals("image", store.read().background)
            assertTrue(store.read().music)
            assertEquals(
                1,
                drain().count {
                    it.component?.className == ClusterRenderService::class.java.name &&
                        it.action == "apply"
                },
            )
            // 实际非法图片：不改原配置、不发 apply，显示原错误提示。
            val invalid = File(sourceDir, "invalid.png").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
            ClusterPageFixture.button(root, "更换图片").performClick()
            activity.deliverActivityResult(41, Activity.RESULT_OK, Intent().putExtra("imagePath", invalid.absolutePath))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(saved.name, store.read().image)
            assertEquals("image", store.read().background)
            assertTrue(
                drain().none {
                    it.component?.className == ClusterRenderService::class.java.name &&
                        it.action == "apply"
                },
            )
            assertEquals("图片无法读取，请选择其他图片", ShadowToast.getTextOfLatestToast())
        } finally {
            controller.pause().stop().destroy()
        }

        // 可控排队调度：新请求替换未执行的旧请求；关闭后完成的任务不改配置、不发命令。
        val controllerQueued = Robolectric.buildActivity(ClusterActivity::class.java)
        val (queuedSession, executor) = ClusterPageFixture.installWithQueuedImport(controllerQueued.get())
        controllerQueued.setup()
        try {
            val activity = controllerQueued.get()
            val root = activity.window.decorView
            val first = writeRealPng(File(sourceDir, "first.png"), 0xFF0000FF)
            val second = writeRealPng(File(sourceDir, "second.png"), 0xFFFF0000)
            ClusterPageFixture.row(root, "原厂增强").performClick()
            ClusterPageFixture.row(root, "背景").performClick()
            // 容量 1 观察点：连续两次选择后、执行前，实际待执行任务不超过 1。
            ClusterPageFixture.button(root, "选择新图片").performClick()
            activity.deliverActivityResult(41, Activity.RESULT_OK, Intent().putExtra("imagePath", first.absolutePath))
            ClusterPageFixture.button(root, "选择新图片").performClick()
            activity.deliverActivityResult(41, Activity.RESULT_OK, Intent().putExtra("imagePath", second.absolutePath))
            assertTrue("两次选择只允许排入一个待执行调度任务", executor.pendingCount <= 1)
            executor.runAll()
            shadowOf(Looper.getMainLooper()).idle()
            val committed = File(app.filesDir, "display-backgrounds").listFiles()!!.map { it.name }
            // 只有最新请求保存并提交；被替换的旧请求从未执行。
            assertEquals(2, committed.size)
            val newFileName = committed.single { it != savedName }
            assertEquals(newFileName, store.read().image)
            assertEquals("image", store.read().background)
            assertTrue(store.read().music)
            assertTrue(
                generateSequence { shadowOf(app).nextStartedService }
                    .toList()
                    .count {
                        it.component?.className == ClusterRenderService::class.java.name && it.action == "apply"
                    } ==
                    1,
            )
            assertEquals(1, queuedSession.connectCount)
            // 执行完成后再选择新图片：pump 标志跨周期复原，仍只排入一个任务并正常提交。
            val third = writeRealPng(File(sourceDir, "third.png"), 0xFF00FF00)
            ClusterPageFixture.button(root, "更换图片").performClick()
            activity.deliverActivityResult(41, Activity.RESULT_OK, Intent().putExtra("imagePath", third.absolutePath))
            assertTrue("新周期同样只排入一个调度任务", executor.pendingCount <= 1)
            executor.runAll()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3, File(app.filesDir, "display-backgrounds").listFiles()!!.size)
            // 第三次选择同样提交：相对上一分段恰好新增一条 apply，且配置指向最新文件。
            assertEquals(1, File(app.filesDir, "display-backgrounds").listFiles()!!.count { it.name !in committed })
            assertEquals(
                1,
                generateSequence { shadowOf(app).nextStartedService }
                    .toList()
                    .count { it.component?.className == ClusterRenderService::class.java.name && it.action == "apply" },
            )
            // 关闭后完成的任务：不写配置、不发命令。
            val beforeClose = File(app.filesDir, "display-backgrounds").listFiles()!!.map { it.name }
            ClusterPageFixture.button(root, "更换图片").performClick()
            activity.deliverActivityResult(41, Activity.RESULT_OK, Intent().putExtra("imagePath", third.absolutePath))
            controllerQueued.pause().stop().destroy()
            executor.runAll()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(beforeClose, File(app.filesDir, "display-backgrounds").listFiles()!!.map { it.name })
            assertTrue(
                generateSequence { shadowOf(app).nextStartedService }
                    .toList()
                    .none { it.component?.className == ClusterRenderService::class.java.name && it.action == "apply" },
            )
        } finally {
            if (!controllerQueued.get().isDestroyed) controllerQueued.pause().stop().destroy()
        }
    }

    /** 丢弃当前累积的服务 Intent，便于分段断言。 */
    private fun drainServices() {
        while (shadowOf(RuntimeEnvironment.getApplication()).nextStartedService != null) {
            // 丢弃已启动记录，不影响后续断言。
        }
    }

    /** 生成确定内容的真实 PNG 文件；颜色取常数便于区分两次导入。 */
    private fun writeRealPng(
        target: File,
        color: Long,
    ): File {
        target.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color.toInt())
        FileOutputStream(target).use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        bitmap.recycle()
        return target
    }
}
