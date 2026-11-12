package com.galaxy.autocluster

import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.autocluster.runtime.ClusterDisplayStore
import com.galaxy.auto.ui.GalaxySettingRow
import com.galaxy.autocluster.ui.InstrumentMusicPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cluster 普通页面既有标准用例：真实 Activity、XML/View Binding、Presenter/Store/导入。
 * 旧反射断言已改为 fixture 投递 → 实际页面可见文案断言，原输入与期望行为保持不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w571dp-h1028dp-280dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterAppNavigationTest {
    @Test
    fun appSeparatesHomeMusicAndBackgroundAndKeepsServiceSettingsOutside() {
        ClusterPageFixture.declareServiceUnbindable()
        val store = ClusterDisplayStore(org.robolectric.RuntimeEnvironment.getApplication())
        store.submitElements(ClusterDisplayConfiguration(mode = ClusterDisplayConfiguration.OEM_ELEMENTS, music = true))
        val controller = Robolectric.buildActivity(ClusterActivity::class.java)
        ClusterPageFixture.install(controller.get())
        controller.setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            fun row(name: String) = ClusterPageFixture.row(root, name)
            ClusterPageFixture.capture(root, "overview")
            assertTrue(ClusterPageFixture.hasText(root, "我的仪表"))
            assertFalse(ClusterPageFixture.walk(root).any { it is InstrumentMusicPreview })
            row("原厂增强").performClick()
            ClusterPageFixture.capture(root, "oem-enhancement")
            row("音乐卡片").performClick()
            ClusterPageFixture.capture(root, "music-preview")
            assertEquals(1, ClusterPageFixture.walk(root).count { it is InstrumentMusicPreview })
            assertFalse(
                ClusterPageFixture.walk(root).filterIsInstance<TextView>().any {
                    it.text.contains("Wi-Fi") || it.text.toString() == "本机 ADB"
                },
            )
            // 背景页与设置页可 inflate，且没有服务设置串入。
            activity.onBackPressed()
            row("背景").performClick()
            ClusterPageFixture.capture(root, "background-page")
            ClusterPageFixture.button(root, "设置").performClick()
            ClusterPageFixture.capture(root, "settings-page")
            ClusterPageFixture.button(root, "主题").performClick()
            ClusterPageFixture.capture(root, "themes-page")
            row("我的主题").performClick()
            assertEquals(
                ThemeActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className,
            )
            // 恢复与结果页可 inflate：enabled 期间首页才有恢复入口。
            ClusterPageFixture.button(root, "仪表").performClick()
            ClusterPageFixture.button(root, "恢复原厂显示").performClick()
            ClusterPageFixture.capture(root, "restore-page")
            ClusterPageFixture.button(root, "恢复原厂").performClick()
            ClusterPageFixture.capture(root, "result-page")
            assertTrue(ClusterPageFixture.hasText(root, "仪表显示状态"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun runtimeUsesRootEnabledAndRequiresAnActualReceipt() {
        ClusterPageFixture.declareServiceUnbindable()
        val store = ClusterDisplayStore(org.robolectric.RuntimeEnvironment.getApplication())
        val configuration = ClusterDisplayConfiguration(mode = ClusterDisplayConfiguration.OEM_ELEMENTS, music = true)
        store.submitElements(configuration)
        val controller = Robolectric.buildActivity(ClusterActivity::class.java)
        val session = ClusterPageFixture.install(controller.get())
        controller.setup()
        try {
            val activity = controller.get()
            ClusterPageFixture.row(activity.window.decorView, "原厂增强").performClick()
            val status = activity.findViewById<TextView>(R.id.oem_status)
            val bundle =
                Bundle().apply {
                    putBundle("features", Bundle().apply { putBoolean("rootEnabled", true) })
                    putBundle("clusterElements", configuration.bundle())
                }
            session.push(bundle)
            assertEquals("配置已保存，等待仪表确认", status.text.toString())
            bundle.putString("clusterElementStatus", "active")
            session.push(bundle)
            assertEquals("原厂增强运行中", status.text.toString())
            bundle.getBundle("features")!!.putBoolean("rootEnabled", false)
            session.push(bundle)
            assertEquals("基础能力已关闭，请在 AutoService 中开启", status.text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun stagedImageIsNotAnEnabledSwitchAndUserControlsSubmitWithoutAnotherApply() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        ClusterPageFixture.declareServiceUnbindable()
        val store = ClusterDisplayStore(app)
        store.saveDraft(ClusterDisplayConfiguration(ClusterDisplayConfiguration.OEM_ELEMENTS, "image", true, "aurora.png"))
        val controller = Robolectric.buildActivity(ClusterActivity::class.java)
        ClusterPageFixture.install(controller.get())
        controller.setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            fun row(name: String) = ClusterPageFixture.row(root, name)
            fun control() = ClusterPageFixture.walk(root).filterIsInstance<Switch>().single()
            fun drain(): List<android.content.Intent> = generateSequence { shadowOf(app).nextStartedService }.toList()
            row("原厂增强").performClick()
            row("音乐卡片").performClick()
            assertFalse(control().isChecked)
            assertFalse(store.enabled)
            drain()
            control().performClick()
            assertTrue(store.enabled)
            assertTrue(store.elements().music)
            // 草稿图片不因音乐开关启用图片背景。
            assertEquals("original", store.elements().background)
            assertTrue(control().isChecked)
            assertTrue(drain().any { it.component?.className == ClusterRenderService::class.java.name && it.action == "apply" })
            activity.onBackPressed()
            row("背景").performClick()
            row("本地图片").performClick()
            assertEquals("image", store.elements().background)
            assertEquals("aurora.png", store.elements().image)
            assertTrue(store.elements().music)
            assertTrue(drain().any { it.component?.className == ClusterRenderService::class.java.name && it.action == "apply" })
            activity.onBackPressed()
            row("音乐卡片").performClick()
            control().performClick()
            assertFalse(store.elements().music)
            assertEquals("image", store.elements().background)
            assertTrue(store.enabled)
            drain()
            activity.onBackPressed()
            row("背景").performClick()
            row("原厂背景").performClick()
            assertFalse(store.enabled)
            assertTrue(drain().any { it.component?.className == ClusterRenderService::class.java.name && it.action == "stop" })
            activity.onBackPressed()
            row("音乐卡片").performClick()
            assertFalse(control().isChecked)
        } finally {
            controller.pause().stop().destroy()
        }
        val recreated = Robolectric.buildActivity(ClusterActivity::class.java)
        ClusterPageFixture.install(recreated.get())
        recreated.setup()
        try {
            assertFalse(ClusterDisplayStore(recreated.get()).enabled)
            val starts = generateSequence { shadowOf(app).nextStartedService }.toList()
            assertFalse(starts.any { it.component?.className == ClusterRenderService::class.java.name })
        } finally {
            recreated.pause().stop().destroy()
        }
    }

    @Test
    fun changedPlanCannotDisplaySuccessFromPreviousImageReceipt() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        ClusterPageFixture.declareServiceUnbindable()
        val store = ClusterDisplayStore(app)
        val current = ClusterDisplayConfiguration(ClusterDisplayConfiguration.OEM_ELEMENTS, "image", true, "new.png")
        store.submitElements(current)
        val controller = Robolectric.buildActivity(ClusterActivity::class.java)
        val session = ClusterPageFixture.install(controller.get())
        controller.setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            ClusterPageFixture.row(root, "原厂增强").performClick()
            ClusterPageFixture.row(root, "背景").performClick()
            val status = activity.findViewById<TextView>(R.id.background_status)
            val snapshot =
                Bundle().apply {
                    putString("clusterElementStatus", "active")
                    putBundle("clusterElements", current.copy(image = "old.png").bundle())
                    putBundle("clusterElementRuntime", Bundle().apply { putBoolean("backgroundMounted", true) })
                }
            session.push(snapshot)
            assertEquals("正在提交仪表配置", status.text.toString())
            snapshot.putBundle("clusterElements", current.bundle())
            session.push(snapshot)
            assertEquals("仪表背景已替换", status.text.toString())
            snapshot.putString("clusterConfigurationStatus", "failed")
            session.push(snapshot)
            assertEquals("背景图片未能应用，显示选择已保留", status.text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
