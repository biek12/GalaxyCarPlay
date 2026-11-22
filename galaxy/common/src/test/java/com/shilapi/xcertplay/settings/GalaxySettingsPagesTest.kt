package com.shilapi.xcertplay.settings

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.galaxy.auto.ui.GalaxySettingRow
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.L7HomePanel
import com.shilapi.xcertplay.L7HotspotActions
import com.shilapi.xcertplay.L7HotspotTask
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.util.Locale

/** 首页、设置分类和无线页使用真实资源和现有控件，不另造一套状态。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "zh-rCN")
class GalaxySettingsPagesTest {
    @Test
    fun homeButtonsFollowConfigurationAndDisableWhilePending() {
        val activity = activity()
        val panel = L7HomePanel(activity, {}, {}, {}, {})
        panel.update(configured = false, running = false, connected = false, pending = false, error = null)
        assertEquals(View.GONE, panel.quick.visibility)
        assertEquals(activity.getString(R.string.l7_home_first_use), status(panel))
        assertTrue(panel.usb.isEnabled)
        panel.update(configured = true, running = false, connected = false, pending = false, error = null)
        assertEquals(View.VISIBLE, panel.quick.visibility)
        assertEquals(activity.getString(R.string.l7_entry_connect), panel.quick.text.toString())
        assertTrue(panel.quick.isEnabled)
        panel.update(configured = true, running = false, connected = false, pending = true, error = null)
        assertEquals(activity.getString(R.string.l7_preparing), panel.quick.text.toString())
        assertFalse(panel.quick.isEnabled)
        assertFalse(panel.usb.isEnabled)
        panel.update(configured = true, running = true, connected = true, pending = false, error = "保持可见")
        assertEquals(activity.getString(R.string.l7_resume_projection), panel.quick.text.toString())
        assertTrue(panel.quick.isEnabled)
        assertEquals("保持可见", status(panel))
    }

    @Test
    fun desktopPermissionHintIsClickableOnlyWhenShown() {
        val activity = activity()
        var clicked = 0
        val parent = LinearLayout(activity)
        val page = GalaxyHomePage(activity, {}, {}, {}, { clicked += 1 })
        page.bind(parent)
        page.showPermissionHint(false)
        assertEquals(View.GONE, page.permissionHint!!.visibility)
        assertFalse(page.permissionHint!!.isClickable)
        page.permissionHint!!.performClick()
        assertEquals(0, clicked)
        page.showPermissionHint(true)
        assertEquals(View.VISIBLE, page.permissionHint!!.visibility)
        assertTrue(page.permissionHint!!.isClickable)
        assertEquals(activity.getString(R.string.l7_desktop_home_permission), page.permissionHint!!.text.toString())
    }

    @Test
    fun settingsMenuKeepsSevenPathsAndRoutesOncePerClick() {
        val activity = activity()
        val selected = mutableListOf<String>()
        val parent = LinearLayout(activity)
        GalaxySettingsMenuPage(activity, selected::add).bind(parent)
        val rows = views(parent).filterIsInstance<GalaxySettingRow>()
        assertEquals(expectedTitles(activity), rows.map { it.titleView.text.toString() }.toList())
        rows.forEach { it.performClick() }
        assertEquals(
            listOf(
                "settings-vehicle",
                "settings-connection",
                "settings-general",
                "settings-permissions",
                "settings-debug",
                "settings-logs",
                "settings-about",
            ),
            selected,
        )
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp")
    fun smallScreenHomeUsesCompactWidth() {
        val activity = activity()
        val parent = LinearLayout(activity)
        GalaxyHomePage(activity, {}, {}, {}, {}).bind(parent)
        assertEquals(288, (parent.getChildAt(0).layoutParams as LinearLayout.LayoutParams).width)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h1280dp")
    fun wideScreenHomeCapsAtFiveHundredSixty() {
        val activity = activity()
        val parent = LinearLayout(activity)
        GalaxyHomePage(activity, {}, {}, {}, {}).bind(parent)
        assertEquals(560, (parent.getChildAt(0).layoutParams as LinearLayout.LayoutParams).width)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w600dp-h900dp", fontScale = 1.3f)
    fun largeFontKeepsHomeActionsReadable() {
        val activity = activity()
        val panel = L7HomePanel(activity, {}, {}, {}, {})
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertEquals(activity.getString(R.string.l7_entry_connect), panel.quick.text.toString())
        assertTrue(panel.quick.measuredHeight >= 48)
        assertTrue(panel.quick.textSize > panel.usb.textSize || panel.quick.textSize > 0f)
    }

    @Test
    fun chineseAndEnglishResourcesBothResolve() {
        val activity = activity()
        assertEquals("立即连接", activity.getString(R.string.l7_entry_connect))
        val english = Configuration(activity.resources.configuration)
        english.setLocale(Locale.ENGLISH)
        val localized = activity.createConfigurationContext(english)
        assertEquals("Connect now", localized.getString(R.string.l7_entry_connect))
        assertNotEquals(activity.getString(R.string.l7_start_wireless), localized.getString(R.string.l7_start_wireless))
    }

    @Test
    fun dayAndNightSettingsColorsDiffer() {
        val day = activity().getColor(R.color.l7_settings_content_top)
        val night = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        night.apply { setTheme(R.style.Theme_Xcertplay) }
        val configuration = Configuration(night.resources.configuration)
        configuration.uiMode =
            Configuration.UI_MODE_NIGHT_YES or (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv())
        val nightResources = night.createConfigurationContext(configuration).resources
        assertNotEquals(day, nightResources.getColor(R.color.l7_settings_content_top, night.theme))
    }

    @Test
    fun wirelessSlotsFollowManualAndDirectAndSaveOnlyOnConfirm() {
        val activity = activity()
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        var confirmed = 0
        val parent = LinearLayout(activity)
        val task = L7HotspotTask(activity)
        try {
            val direct = bindWireless(activity, parent, task) { confirmed += 1 }
            assertNull(direct.hotspot)
            assertEquals(activity.getString(R.string.wifi_direct), text(parent, R.string.wifi_direct))
            clickRow(parent, activity.getString(R.string.wireless_connection_method))
            dismissDialog(android.app.AlertDialog.BUTTON_NEGATIVE)
            assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(activity))
            assertEquals(0, confirmed)
            direct.wireless.dispose()
            parent.removeAllViews()
            AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
            val manual = bindWireless(activity, parent, task) { confirmed += 1 }
            assertEquals(0, confirmed)
            assertTrue(manual.hotspot != null)
            assertEquals(activity.getString(R.string.l7_wireless_steps), text(parent, R.string.l7_wireless_steps))
            manual.wireless.dispose()
            manual.hotspot?.dispose()
        } finally {
            task.close()
        }
    }

    @Test
    fun connectionRowAndButtonsReflectPendingAndMissingSession() {
        val activity = activity()
        val parent = LinearLayout(activity)
        val row = GalaxyConnectionPage.bindChoices(activity, parent, {}, {}, {}, {})
        val actions =
            GalaxyConnectionActions(
                session =
                    GalaxyConnectionSession(
                        hasSession = { false },
                        active = { false },
                        reconnectable = { false },
                        currentWireless = { null },
                    ),
                platform = GalaxyConnectionPlatform(),
            )
        actions.applyReconnect(row, null)
        assertFalse(row.isEnabled)
        assertEquals(activity.getString(R.string.l7_reconnect_no_session), row.feedbackView.text.toString())
        val wirelessParent = LinearLayout(activity)
        val task = L7HotspotTask(activity)
        try {
            AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
            val bound = bindWireless(activity, wirelessParent, task) {}
            actions.applyButtons(bound.connect, bound.disconnect, null, null, home = false)
            assertEquals(activity.getString(R.string.l7_start_wireless), bound.connect.text.toString())
            assertTrue(bound.connect.isEnabled)
            actions.pending = true
            actions.applyButtons(bound.connect, bound.disconnect, null, null, home = false)
            assertEquals(activity.getString(R.string.l7_preparing), bound.connect.text.toString())
            assertFalse(bound.connect.isEnabled)
            bound.wireless.dispose()
            bound.hotspot?.dispose()
        } finally {
            task.close()
        }
        val auto =
            views(parent).filterIsInstance<GalaxySettingRow>().first {
                it.titleView.text.toString() ==
                    activity.getString(R.string.connect_when_diplay_opens)
            }
        val toggle = views(auto).filterIsInstance<CompoundButton>().single()
        assertFalse(DiPlayPreferences.autoConnect(activity))
        toggle.performClick()
        assertTrue(DiPlayPreferences.autoConnect(activity))
        auto.setSwitchChecked(false)
        assertTrue(DiPlayPreferences.autoConnect(activity))
    }

    private fun bindWireless(
        activity: ComponentActivity,
        parent: LinearLayout,
        task: L7HotspotTask,
        onModeConfirmed: () -> Unit,
    ) = GalaxyConnectionPage.bindWireless(
        activity,
        parent,
        canEdit = { true },
        onModeConfirmed = onModeConfirmed,
        choosePhone = {},
        onEditHotspot = {},
        onConnect = {},
        onOpenProjection = {},
        onDisconnect = {},
        hotspotTask = task,
        hotspotActions = L7HotspotActions(activity, task),
    )

    private fun dismissDialog(button: Int) {
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val label =
            dialog.context.getString(
                if (button == android.app.AlertDialog.BUTTON_POSITIVE) R.string.save else R.string.cancel,
            )
        views(dialog.window!!.decorView)
            .filterIsInstance<android.widget.Button>()
            .first {
                it.text.toString() == label
            }.performClick()
    }

    private fun activity() =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().apply {
            setTheme(R.style.Theme_Xcertplay)
        }

    private fun status(panel: L7HomePanel) =
        views(panel)
            .filterIsInstance<TextView>()
            .map { it.text.toString() }
            .first {
                it == panel.context.getString(R.string.l7_home_first_use) ||
                    it == "保持可见" ||
                    it == panel.context.getString(R.string.ready_when_you_are) ||
                    it == panel.context.getString(R.string.l7_session_connected_hint) ||
                    it == panel.context.getString(R.string.l7_session_waiting_hint)
            }

    private fun text(root: View, id: Int) =
        views(root)
            .filterIsInstance<TextView>()
            .first {
                it.text.toString() ==
                    root.context.getString(id)
            }.text
            .toString()

    private fun clickRow(root: View, title: String) {
        views(root).filterIsInstance<GalaxySettingRow>().first { it.titleView.text.toString() == title }.performClick()
    }

    private fun expectedTitles(activity: ComponentActivity) =
        listOf(
            R.string.config_page_title,
            R.string.connection_setup,
            R.string.application_settings_title,
            R.string.permissions_and_connection_help,
            R.string.l7_probe_title,
            R.string.l7_logs_title,
            R.string.about,
        ).map { activity.getString(it) }

    private fun views(root: View): Sequence<View> =
        sequence {
            yield(root)
            if (root is ViewGroup) {
                for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
            }
        }
}
