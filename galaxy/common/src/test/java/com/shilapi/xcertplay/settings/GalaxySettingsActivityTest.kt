package com.shilapi.xcertplay.settings

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.GalaxySettingsActivity
import com.shilapi.xcertplay.L7Agreement
import com.shilapi.xcertplay.L7Authentication
import com.shilapi.xcertplay.L7ProjectionNavigation
import com.shilapi.xcertplay.L7UiDensity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.File
import java.io.FileOutputStream

/** 通过真实设置 Activity 覆盖换页、返回、滚动、菜单重建，并写出 1080×1800 截图。 */
@RunWith(RobolectricTestRunner::class)
class GalaxySettingsActivityTest {
    @After
    fun clearSession() {
        CarPlayBackgroundSession.clear()
    }

    @Test
    @Config(sdk = [30], qualifiers = "zh-rCN-w1080dp-h1800dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun clicksBackScrollAndSameDensityRefreshKeepTheShell() {
        val activity = launch("home")
        capture(activity, "m1-home-light.png")
        click(activity, activity.getString(R.string.settings))
        idle()
        assertTrue(contains(activity, activity.getString(R.string.l7_settings_navigation_hint)))
        capture(activity, "m1-settings-light.png")
        click(activity, activity.getString(R.string.connection_setup))
        idle()
        assertTrue(contains(activity, activity.getString(R.string.l7_connection_choose)))
        capture(activity, "m1-connection-light.png")
        click(activity, activity.getString(R.string.l7_start_wireless))
        idle()
        assertTrue(contains(activity, activity.getString(R.string.l7_connection_wireless_hint)))
        clickDescription(activity, activity.getString(R.string.l7_connection_back))
        idle()
        assertTrue(contains(activity, activity.getString(R.string.l7_connection_choose)))
        clickDescription(activity, activity.getString(R.string.l7_back_settings))
        idle()
        listOf(
            R.string.config_page_title,
            R.string.application_settings_title,
            R.string.permissions_and_connection_help,
            R.string.l7_probe_title,
            R.string.l7_logs_title,
        ).forEach { title ->
            click(activity, activity.getString(title))
            idle()
            assertTrue(contains(activity, activity.getString(title)))
            clickDescription(activity, activity.getString(R.string.l7_back_settings))
            idle()
            assertTrue(contains(activity, activity.getString(R.string.l7_settings_navigation_hint)))
        }
        val before = navigation(activity)
        activity.onConfigurationChanged(activity.resources.configuration)
        idle()
        assertSame(before, navigation(activity))
    }

    @Test
    @Config(sdk = [30], qualifiers = "zh-rCN-w360dp-h480dp")
    fun scrollAndDensityRebuildRestoreTheOpenPage() {
        val controller =
            Robolectric.buildActivity(
                GalaxySettingsActivity::class.java,
                Intent().putExtra("page", "settings"),
            )
        prepare()
        val activity = controller.setup().get()
        idle()
        val scroll = scroll(activity)
        val width = View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
        activity.window.decorView.measure(width, height)
        activity.window.decorView.layout(0, 0, 720, 240)
        scroll.scrollTo(0, 80)
        assertTrue(scroll.getChildAt(0).measuredHeight > scroll.measuredHeight)
        assertTrue(scroll.scrollY > 0)
        val previous = navigation(activity)
        val expandedBefore = previous.expanded
        val config = android.content.res.Configuration(activity.resources.configuration)
        config.densityDpi += 40
        activity.onConfigurationChanged(config)
        idle()
        assertNotSame(previous, navigation(activity))
        assertEquals(expandedBefore, navigation(activity).expanded)
        val restarted =
            Robolectric.buildActivity(
                GalaxySettingsActivity::class.java,
                Intent().putExtra("page", "settings"),
            )
        prepare()
        val first = restarted.setup().get()
        idle()
        val scroller = scroll(first)
        val restartWidth = View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        val restartHeight = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        first.window.decorView.measure(restartWidth, restartHeight)
        first.window.decorView.layout(0, 0, 720, 360)
        scroller.scrollTo(0, 80)
        val recreated = restarted.recreate().get()
        idle()
        assertTrue(contains(recreated, recreated.getString(R.string.l7_settings_navigation_hint)))
        assertEquals(navigation(first).expanded, navigation(recreated).expanded)
        assertTrue(scroller.scrollY > 0)
    }

    @Test
    @Config(sdk = [30], qualifiers = "zh-rCN-w1080dp-h1800dp-night")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nightScreenshotsShowHomeAndBothWirelessModes() {
        val home = launch("home")
        capture(home, "m1-home-night.png")
        val manual = launch("settings-connection-wireless")
        capture(manual, "m1-wireless-manual-night.png")
        val direct = launch("settings-connection-wireless", WirelessHotspotMode.WIFI_P2P)
        capture(direct, "m1-wireless-p2p-night.png")
    }

    private fun launch(
        page: String,
        mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
    ): GalaxySettingsActivity {
        prepare()
        AirPlayPersistence.saveWirelessHotspotMode(RuntimeEnvironment.getApplication(), mode)
        val activity =
            Robolectric
                .buildActivity(
                    GalaxySettingsActivity::class.java,
                    Intent().putExtra("page", page),
                ).setup()
                .get()
        idle()
        return activity
    }

    private fun prepare() {
        val app = RuntimeEnvironment.getApplication() as Application
        CarPlayBackgroundSession.clear()
        L7Agreement.accept(app)
        L7Authentication.selectExternal(app, L7Authentication.Source.USB, "", "")
        DiPlayPreferences.saveAutoConnect(app, false)
        L7UiDensity.save(app, app.resources.configuration.densityDpi)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
    }

    private fun capture(activity: GalaxySettingsActivity, name: String) {
        idle()
        val content = activity.findViewById<View>(android.R.id.content)
        val width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY)
        content.measure(width, height)
        content.layout(0, 0, 1080, 1800)
        check(content.width > 0 && content.height > 0) { "screenshot page has no size" }
        val bitmap = Bitmap.createBitmap(1080, 1800, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(bitmap))
        val directory = File(System.getenv("GALAXY_M1_SCREENSHOT_DIR") ?: ".work/m1-delivery/screenshots")
        directory.mkdirs()
        FileOutputStream(File(directory, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun click(activity: GalaxySettingsActivity, label: String) {
        val match =
            views(activity.window.decorView).first {
                it is TextView && it.text.toString() == label || it.contentDescription?.toString() == label
            }
        var target: View? = match
        while (target != null && !target.isClickable && !target.isFocusable) target = target.parent as? View
        (target ?: match).performClick()
    }

    private fun clickDescription(activity: GalaxySettingsActivity, label: String) {
        views(
            activity.window.decorView,
        ).first { it.contentDescription?.toString() == label && it.isClickable }.performClick()
    }

    private fun contains(activity: GalaxySettingsActivity, text: String) =
        views(activity.window.decorView).any { it is TextView && it.text.toString() == text }

    private fun navigation(activity: GalaxySettingsActivity) =
        views(activity.window.decorView).filterIsInstance<L7ProjectionNavigation>().single()

    private fun scroll(activity: GalaxySettingsActivity) =
        views(activity.window.decorView).filterIsInstance<ScrollView>().first { it.visibility == View.VISIBLE }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun views(root: View): Sequence<View> =
        sequence {
            yield(root)
            if (root is ViewGroup) {
                for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
            }
        }
}
