package com.shilapi.xcertplay.settings

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlaySessionDisplay
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.GalaxySettingsActivity
import com.shilapi.xcertplay.L7Agreement
import com.shilapi.xcertplay.L7Authentication
import com.shilapi.xcertplay.L7ProjectionNavigation
import com.shilapi.xcertplay.L7UiDensity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
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
    @Config(sdk = [30], qualifiers = "zh-rCN-w1080dp-h1800dp")
    fun pictureFromSettingsReturnsHomeOrTheExistingHostWithoutChangingSession() {
        val plain = launch("settings")
        clickDescription(plain, plain.getString(R.string.l7_nav_picture))
        idle()
        assertTrue(contains(plain, plain.getString(R.string.l7_entry_title)))
        assertNull(shadowOf(plain).nextStartedActivity)

        val waiting = launchWithSession("settings", active = false)
        clickDescription(waiting.activity, waiting.activity.getString(R.string.l7_nav_picture))
        idle()
        assertHostOpened(waiting.activity)
        waiting.session.assertUntouched()
        assertTrue(contains(waiting.activity, waiting.activity.getString(R.string.l7_settings_navigation_hint)))

        val connected = launchWithSession("settings", active = true)
        clickDescription(connected.activity, connected.activity.getString(R.string.l7_nav_picture_connected))
        idle()
        assertHostOpened(connected.activity)
        connected.session.assertUntouched()
        assertTrue(contains(connected.activity, connected.activity.getString(R.string.l7_settings_navigation_hint)))
    }

    @Test
    @Config(sdk = [30], qualifiers = "zh-rCN-w360dp-h480dp")
    fun repeatedRefreshPageReturnAndDensityDoNotReplayAnOldScroll() {
        val activity = launch("settings")
        repeat(8) {
            activity.onConfigurationChanged(activity.resources.configuration)
            idle()
        }
        val scroll = scroll(activity)
        assertTrue(layoutListeners(scroll) <= 1)
        measure(activity, 720, 240)
        idle()
        assertEquals(0, layoutListeners(scroll))
        scroll.scrollTo(0, 80)
        val kept = scroll.scrollY
        assertTrue(kept > 0)
        measure(activity, 720, 200)
        idle()
        assertEquals(kept, scroll.scrollY)

        clickDescription(activity, activity.getString(R.string.l7_nav_picture))
        idle()
        click(activity, activity.getString(R.string.settings))
        idle()
        val returned = scroll(activity)
        measure(activity, 720, 240)
        idle()
        returned.scrollTo(0, 30)
        val afterReturn = returned.scrollY
        assertTrue(afterReturn > 0)
        measure(activity, 720, 180)
        idle()
        assertEquals(afterReturn, returned.scrollY)

        val previous = navigation(activity)
        val config = android.content.res.Configuration(activity.resources.configuration)
        config.densityDpi += 40
        activity.onConfigurationChanged(config)
        idle()
        assertNotSame(previous, navigation(activity))
        val rebuilt = scroll(activity)
        measure(activity, 720, 240)
        idle()
        rebuilt.scrollTo(0, 50)
        val afterDensity = rebuilt.scrollY
        assertTrue(afterDensity > 0)
        measure(activity, 720, 160)
        idle()
        assertEquals(afterDensity, rebuilt.scrollY)
        assertTrue(layoutListeners(rebuilt) <= 1)
    }

    @Test
    @Config(sdk = [30])
    fun shellDropsStaleScrollRestoreAndKeepsTheVehicleScroller() {
        val activity = launch("home")
        val shell = GalaxySettingsShell(activity) { }
        showBody(shell, activity, "settings", 20, 0)
        repeat(8) { showBody(shell, activity, "settings", 20, 0) }
        val settingsScroll = requireNotNull(shell.contentScroll)
        assertEquals(1, layoutListeners(settingsScroll))
        layoutScroll(settingsScroll, 1080, 400)
        idle()
        assertEquals(0, layoutListeners(settingsScroll))

        showBody(shell, activity, "settings", 2000, 200)
        showBody(shell, activity, "settings", 2000, 40)
        val latest = requireNotNull(shell.contentScroll)
        assertEquals(1, layoutListeners(latest))
        layoutScroll(latest, 1080, 400)
        idle()
        assertEquals(40, latest.scrollY)
        assertEquals(0, layoutListeners(latest))
        latest.scrollTo(0, 10)
        layoutScroll(latest, 1080, 420)
        idle()
        assertEquals(10, latest.scrollY)

        showBody(shell, activity, "home", 2000, 0)
        val homeScroll = requireNotNull(shell.contentScroll)
        layoutScroll(homeScroll, 1080, 360)
        idle()
        assertEquals(0, homeScroll.scrollY)
        homeScroll.scrollTo(0, 25)
        layoutScroll(homeScroll, 1080, 340)
        idle()
        assertEquals(25, homeScroll.scrollY)

        var vehicleScroll: ScrollView? = null
        shell.show(
            page = "settings-vehicle",
            settingsPage = true,
            title = "车辆",
            backLabel = "返回",
            onBack = null,
            menuExpanded = false,
            scrollPositions = emptyMap(),
            installVehicle = { host ->
                val scroller = ScrollView(activity)
                scroller.addView(View(activity), LinearLayout.LayoutParams(10, 10))
                host.addView(scroller, FrameLayout.LayoutParams(-1, -1))
                vehicleScroll = scroller
                scroller
            },
            onScroll = {},
        ) { }
        assertEquals(View.GONE, settingsScroll.visibility)
        assertEquals(0, layoutListeners(settingsScroll))
        assertSame(vehicleScroll, shell.contentScroll)
        assertNotSame(settingsScroll, shell.contentScroll)

        shell.discardForDensity()
        showBody(shell, activity, "settings", 2000, 25)
        val rebuilt = requireNotNull(shell.contentScroll)
        assertNotSame(settingsScroll, rebuilt)
        layoutScroll(rebuilt, 1080, 400)
        idle()
        rebuilt.scrollTo(0, 15)
        layoutScroll(rebuilt, 1080, 380)
        idle()
        assertEquals(15, rebuilt.scrollY)
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
        return start(page)
    }

    private fun launchWithSession(page: String, active: Boolean): SessionLaunch {
        prepare()
        val session = RecordingSession(active)
        return SessionLaunch(start(page), session)
    }

    private fun start(page: String): GalaxySettingsActivity {
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

    private fun assertHostOpened(activity: GalaxySettingsActivity) {
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, intent?.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT, intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }

    private fun measure(activity: GalaxySettingsActivity, widthPx: Int, heightPx: Int) {
        val width = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        activity.window.decorView.measure(width, height)
        activity.window.decorView.layout(0, 0, widthPx, heightPx)
    }

    private fun showBody(
        shell: GalaxySettingsShell,
        activity: GalaxySettingsActivity,
        page: String,
        childHeight: Int,
        saved: Int,
    ) {
        shell.show(
            page = page,
            settingsPage = page.startsWith("settings"),
            title = "标题",
            backLabel = "返回",
            onBack = null,
            menuExpanded = false,
            scrollPositions = mapOf(page to saved),
            installVehicle = null,
            onScroll = {},
        ) { content ->
            content.addView(
                View(activity),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, childHeight),
            )
        }
    }

    private fun layoutScroll(scroll: ScrollView, widthPx: Int, heightPx: Int) {
        val width = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        scroll.measure(width, height)
        scroll.layout(0, 0, widthPx, heightPx)
    }

    private fun layoutListeners(view: View): Int {
        val info =
            View::class.java
                .getDeclaredField("mListenerInfo")
                .apply { isAccessible = true }
                .get(view) ?: return 0
        val listeners =
            info.javaClass
                .getDeclaredField("mOnLayoutChangeListeners")
                .apply { isAccessible = true }
                .get(info)
        return (listeners as? List<*>)?.size ?: 0
    }

    private data class SessionLaunch(
        val activity: GalaxySettingsActivity,
        val session: RecordingSession,
    )

    private class RecordingSession(
        active: Boolean,
    ) {
        private val controller = mock(CarPlayController::class.java)
        private var stops = 0

        init {
            CarPlayBackgroundSession.store(
                controller,
                mock(AndroidMediaSink::class.java),
                800,
                480,
                Any(),
                CarPlaySessionDisplay(800, 480, 0, false, false, 800, 480),
            ) { stops++ }
            CarPlayBackgroundSession.active = active
        }

        fun assertUntouched() {
            assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
            assertEquals(0, stops)
            assertTrue(CarPlayBackgroundSession.hasSession())
            assertFalse(CarPlayBackgroundSession.isStopping())
        }
    }

    private fun views(root: View): Sequence<View> =
        sequence {
            yield(root)
            if (root is ViewGroup) {
                for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
            }
        }
}
