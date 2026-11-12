package com.galaxy.autocluster

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.autocluster.runtime.ClusterDisplayStore
import com.galaxy.autocluster.ui.navigation.ClusterPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * R0-01：旧整屏覆盖投屏的配置、页面与命令入口不能复活输出。
 *
 * 全部走真实 [ClusterDisplayStore] / [ClusterDisplayActions] / [ClusterRenderService]
 * 与真实 Activity XML/Presenter；只把 AutoClient 连接边界声明为不可绑定、把
 * 服务启动记录交给 Robolectric shadow，不替换任何模式判定逻辑。
 * 同时验证 OEM 显式提交仍按原合同启动元素服务（R0-03 保留能力）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w571dp-h1028dp-280dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegacyProjectionRetirementTest {
    /** 写入归档前的旧持久配置与草稿，保持 prefs/草稿/主题文件不变。 */
    private fun saveLegacyOverlay() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("cluster-display", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs
            .edit()
            .putString("mode", ClusterDisplayConfiguration.OVERLAY)
            .putBoolean("enabled", true)
            .putString("draft", """{"mode":"overlay","background":"original","music":true,"image":""}""")
            .putString("theme", """{"panel":"MUSIC"}""")
            .commit()
    }

    /** 只统计发往本 App RenderService 的启动记录，不消耗其他断言所需的产品行为。 */
    private fun renderCommands(): List<Intent> =
        generateSequence { shadowOf(RuntimeEnvironment.getApplication()).nextStartedService }
            .toList()
            .filter { it.component?.className == ClusterRenderService::class.java.name }

    @Test
    fun legacyOverlayPreferencesDoNotStartElementsOrProjectionOutput() {
        saveLegacyOverlay()
        ClusterPageFixture.declareServiceUnbindable()
        val app = RuntimeEnvironment.getApplication()

        val controller = Robolectric.buildActivity(ClusterActivity::class.java)
        ClusterPageFixture.install(controller.get())
        controller.setup()
        try {
            val root = controller.get().window.decorView
            assertTrue("旧配置下首页仍可达", ClusterPageFixture.hasText(root, "我的仪表"))
            assertFalse(
                "主导航不得再有主题按钮",
                ClusterPageFixture.walk(root).filterIsInstance<Button>().any { it.text.toString() == "主题" },
            )
            assertTrue(
                "首页展示只读归档提示",
                ClusterPageFixture
                    .walk(root)
                    .filterIsInstance<TextView>()
                    .any {
                        it.text.toString() ==
                            controller.get().getString(
                                R.string.cluster_projection_archived_note,
                            )
                    },
            )
            assertEquals("旧配置不得触发恢复输出", 0, renderCommands().size)
            assertEquals(
                "原持久 mode 保持历史值",
                ClusterDisplayConfiguration.OVERLAY,
                ClusterDisplayStore(app).read().mode,
            )
            assertFalse("旧 OVERLAY 不得算作已启用 OEM", ClusterDisplayStore(app).oemEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun legacyServiceCommandsReturnNotStickyWithoutAnyClient() {
        saveLegacyOverlay()
        ClusterPageFixture.declareServiceUnbindable()
        val service = Robolectric.buildService(ClusterRenderService::class.java).create().get()
        try {
            for (action in listOf(null, "apply", "reload", "sync-energy")) {
                val intent = action?.let { Intent().setAction(it) }
                assertEquals(
                    "旧命令 $action 必须 START_NOT_STICKY",
                    Service.START_NOT_STICKY,
                    service.onStartCommand(intent, 0, 1),
                )
            }
            val panel = Intent().putExtra("panel", "TIRES")
            assertEquals(
                "旧 panel 命令必须 START_NOT_STICKY",
                Service.START_NOT_STICKY,
                service.onStartCommand(panel, 0, 1),
            )
            assertEquals("旧模式不得启动任何 ELEMENTS client 输出", 0, renderCommands().size)
        } finally {
            service.onDestroy()
        }
    }

    @Test
    fun explicitOemSubmissionStillStartsElements() {
        val app = RuntimeEnvironment.getApplication()
        ClusterPageFixture.declareServiceUnbindable()
        ClusterDisplayStore(app).submitElements(
            ClusterDisplayConfiguration(mode = ClusterDisplayConfiguration.OEM_ELEMENTS, music = true),
        )
        val controller = Robolectric.buildActivity(ClusterActivity::class.java)
        ClusterPageFixture.install(controller.get())
        controller.setup()
        try {
            // 真实已启用的 OEM 方案：空 Intent 恢复仍按原合同启动服务。
            assertEquals(1, renderCommands().count { it.action == null })
            assertTrue(ClusterDisplayStore(app).oemEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun savedThemesPageValueFallsBackToHome() {
        assertSame("旧 page=themes 必须按未知值规则回首页", ClusterPage.HOME, ClusterPage.from("themes"))
        saveLegacyOverlay()
        ClusterPageFixture.declareServiceUnbindable()
        val controller = Robolectric.buildActivity(ClusterActivity::class.java)
        ClusterPageFixture.install(controller.get())
        controller.create(Bundle().apply { putString("page", "themes") }).start().resume()
        try {
            val root = controller.get().window.decorView
            assertTrue(ClusterPageFixture.hasText(root, "我的仪表"))
            assertFalse(
                ClusterPageFixture
                    .walk(root)
                    .filterIsInstance<Button>()
                    .any { it.text.toString() == "主题" },
            )
            assertEquals(0, renderCommands().size)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
