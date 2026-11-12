package com.ecarx.autoservice.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.ecarx.autoservice.DiagnosticActivity
import com.ecarx.autoservice.R
import com.ecarx.autoservice.ServiceActivity
import com.ecarx.autoservice.remote.RemoteSettings
import com.ecarx.autoservice.settings.ServiceFeatures
import com.galaxy.auto.client.vehicle.VehicleAccountActivity
import com.galaxy.auto.ui.GalaxySettingRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowToast
import java.time.Duration
import com.galaxy.auto.ui.R as UiR

/**
 * 首页行为用例：真实 `ServiceActivity` + 真实 XML/binding/Presenter/Actions，
 * 只把 AutoClient 的系统边界替换为记录型会话。
 *
 * 覆盖 SV-01 状态与回填、SV-02 用户动作与全部关闭、SV-03 导航/Wi-Fi、SV-04 远程状态与生命周期。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ServiceHomeBehaviorTest {
    private lateinit var session: RecordingSession
    private lateinit var controller: ActivityController<ServiceActivity>
    private lateinit var activity: ServiceActivity

    /** 记录调用并允许测试直接驱动生产回调的会话替身。 */
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

        /** 模拟连接状态回调。 */
        fun pushState(value: String) = onState?.invoke(value) ?: Unit
    }

    private data class ShellCall(
        val transport: String,
        val command: String,
        val result: (Bundle) -> Unit,
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("platform", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context
            .getSharedPreferences("remote-access", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        session = RecordingSession()
        controller = Robolectric.buildActivity(ServiceActivity::class.java)
        activity = controller.get()
        activity.sessionFactory = { _, onSnapshot, onState ->
            session.onSnapshot = onSnapshot
            session.onState = onState
            session
        }
        controller.create().start().resume()
    }

    @After
    fun tearDown() {
        if (!activity.isDestroyed) controller.pause().stop().destroy()
    }

    /** 读取指定 id 的文本。 */
    private fun text(id: Int): String = activity.findViewById<TextView>(id).text.toString()

    /** 读取指定页面设置行操作槽里的开关。 */
    private fun control(root: Activity, rowId: Int): Switch {
        val slot = root.findViewById<ViewGroup>(rowId).findViewById<ViewGroup>(UiR.id.setting_row_accessory)
        return slot.getChildAt(0) as Switch
    }

    /** 当前页面上的开关。 */
    private fun control(rowId: Int): Switch = control(activity, rowId)

    /** 取出一条已启动的服务 Intent。 */
    private fun nextService(): Intent? = shadowOf(activity).nextStartedService

    /** 清空并返回本次累积的服务 Intent。 */
    private fun drainServices(): List<Intent> = generateSequence { nextService() }.toList()

    /** 构造服务快照；字段名与 AutoService.publish 一致。 */
    private fun snapshot(
        foundation: String = "{}",
        rootReady: Boolean = false,
        qnxReady: Boolean = false,
        projectionRequested: Boolean = false,
        model: String = "L6",
        remoteState: String? = null,
        vehiclePropertyCount: Int = 0,
        vehicleValidCount: Int = 0,
        vehicleSampledCount: Int = 0,
        vehicleScanning: Boolean = false,
    ) = Bundle().apply {
        putString("foundation", foundation)
        putBoolean("rootReady", rootReady)
        putBoolean("qnxReady", qnxReady)
        putBoolean("projectionRequested", projectionRequested)
        putString("model", model)
        remoteState?.let { putString("remoteState", it) }
        putInt("vehiclePropertyCount", vehiclePropertyCount)
        putInt("vehicleValidCount", vehicleValidCount)
        putInt("vehicleSampledCount", vehicleSampledCount)
        putBoolean("vehicleScanning", vehicleScanning)
    }

    @Test
    fun featureRowsDeclaredInXmlResolveTitleAndIcon() {
        val projection = activity.findViewById<GalaxySettingRow>(R.id.service_projection)
        assertEquals(activity.getString(R.string.service_feature_projection), projection.titleView.text.toString())
        assertEquals(View.VISIBLE, projection.findViewById<View>(UiR.id.setting_row_icon).visibility)
        val vehicle = activity.findViewById<GalaxySettingRow>(R.id.service_vehicle)
        assertEquals(activity.getString(R.string.service_feature_vehicle), vehicle.titleView.text.toString())
        assertEquals(View.VISIBLE, vehicle.findViewById<View>(UiR.id.setting_row_icon).visibility)
    }

    /**
     * 真实 XML/Activity 设置行默认样式（R2-S3）：orientation/padding/minHeight 来自平台四参
     * 构造的 `GalaxyUi.SettingRow`，不再经过任何手工属性解析；代码构造行共用同一默认来源。
     */
    @Test
    fun settingRowsUsePlatformDefaultStyleForLayoutAttributes() {
        fun px(id: Int) = activity.resources.getDimensionPixelSize(id)
        val row = activity.findViewById<GalaxySettingRow>(R.id.service_vehicle)
        assertEquals("默认水平排布", LinearLayout.HORIZONTAL, row.orientation)
        // 样式只声明 center_vertical；LinearLayout 布局时为水平补默认 START，垂直仍居中。
        assertEquals("默认垂直居中", Gravity.START or Gravity.CENTER_VERTICAL, row.gravity)
        assertEquals(px(UiR.dimen.galaxy_ui_row_padding_horizontal), row.paddingStart)
        assertEquals(px(UiR.dimen.galaxy_ui_row_padding_horizontal), row.paddingEnd)
        assertEquals(px(UiR.dimen.galaxy_ui_row_padding_vertical), row.paddingTop)
        assertEquals(px(UiR.dimen.galaxy_ui_row_padding_vertical), row.paddingBottom)
        assertEquals("带说明的行最小 96dp", px(UiR.dimen.galaxy_ui_row_min_height_detail), row.minimumHeight)

        val codeRow = GalaxySettingRow(activity, "标题", "说明")
        assertEquals(LinearLayout.HORIZONTAL, codeRow.orientation)
        assertEquals(Gravity.START or Gravity.CENTER_VERTICAL, codeRow.gravity)
        assertEquals(px(UiR.dimen.galaxy_ui_row_padding_horizontal), codeRow.paddingStart)
        assertEquals(px(UiR.dimen.galaxy_ui_row_padding_vertical), codeRow.paddingTop)
        assertEquals(px(UiR.dimen.galaxy_ui_row_min_height_detail), codeRow.minimumHeight)

        // 开关与图标不受样式来源变化影响：真实行内仍能取到开关并回填。
        assertTrue(control(R.id.service_projection).isChecked)
        assertEquals(View.VISIBLE, row.findViewById<View>(UiR.id.setting_row_icon).visibility)
    }

    @Test
    fun switchesReflectSettingsAndSnapshotWithoutDispatchingCommands() {
        assertEquals(1, session.connects)
        assertTrue(control(R.id.service_projection).isChecked)
        assertTrue(control(R.id.service_automatic).isChecked)
        assertEquals(activity.getString(R.string.service_headline_waiting), text(R.id.service_headline))
        assertEquals(activity.getString(R.string.service_badge_connecting), text(R.id.service_badge))
        assertTrue("状态回填不得发命令", drainServices().isEmpty())

        session.push(
            snapshot(
                foundation = """{"state":"verified","adbUid":0,"tlsPort":5555}""",
                rootReady = true,
            ),
        )
        assertEquals(activity.getString(R.string.service_headline_running), text(R.id.service_headline))
        assertEquals(activity.getString(R.string.service_badge_connected), text(R.id.service_badge))
        assertEquals(activity.getString(R.string.service_state_connected), text(R.id.service_adb_state))
        assertEquals(activity.getString(R.string.service_state_connected), text(R.id.service_root_state))
        assertEquals(
            activity.getString(R.string.service_metric_waiting_projection),
            text(R.id.service_projection_state),
        )
        assertTrue(text(R.id.service_summary).contains("L6"))
        assertTrue("快照渲染不得发命令", drainServices().isEmpty())
    }

    @Test
    fun connectionLossFallsBackToWaitingWithoutStoppingService() {
        session.push(snapshot(rootReady = true))
        assertEquals(activity.getString(R.string.service_headline_running), text(R.id.service_headline))
        session.pushState("service_disconnected")
        assertEquals(activity.getString(R.string.service_headline_waiting), text(R.id.service_headline))
        assertTrue("断开只更新页面，不发 stop", drainServices().isEmpty())
    }

    @Test
    fun wifiHelpFollowsFeatureReadinessAndWifiState() {
        val wifi = activity.getSystemService(android.net.wifi.WifiManager::class.java)
        wifi.setWifiEnabled(false)
        session.push(snapshot(foundation = """{"state":"failed"}"""))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.service_wifi_help).visibility)
        assertEquals(
            activity.getString(R.string.service_wifi_off),
            activity.findViewById<TextView>(R.id.service_wifi_message).text.toString(),
        )

        wifi.setWifiEnabled(true)
        session.push(snapshot(foundation = """{"state":"failed"}"""))
        assertEquals(
            activity.getString(R.string.service_wifi_pending),
            activity.findViewById<TextView>(R.id.service_wifi_message).text.toString(),
        )

        // 后台暂停时不显示连接帮助。
        activity
            .getSharedPreferences("platform", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("automatic", false)
            .commit()
        session.push(snapshot(foundation = """{"state":"failed"}"""))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.service_wifi_help).visibility)
    }

    @Test
    fun userActionsPersistBeforeDispatchAndDisableAllCoversEverything() {
        control(R.id.service_automatic).performClick()
        val platform = activity.getSharedPreferences("platform", Context.MODE_PRIVATE)
        assertFalse(platform.getBoolean("automatic", true))
        assertEquals("stop", drainServices().single().action)

        control(R.id.service_projection).performClick()
        assertFalse(platform.getBoolean("projectionEnabled", true))
        assertEquals(ServiceFeatures.ACTION_CHANGED, drainServices().single().action)

        activity.findViewById<View>(R.id.service_disable_all).performClick()
        assertFalse(platform.getBoolean("automatic", true))
        assertFalse(platform.getBoolean("projectionEnabled", true))
        assertFalse(platform.getBoolean("vehicleEnabled", true))
        assertFalse(platform.getBoolean("adbEnabled", true))
        assertFalse(platform.getBoolean("rootEnabled", true))
        assertEquals(0L, RemoteSettings(activity).until)
        assertEquals("stop", drainServices().single().action)
    }

    @Test
    fun recreatedPageDoesNotStartBackgroundWork() {
        activity.findViewById<View>(R.id.service_disable_all).performClick()
        drainServices()
        controller.pause().stop().destroy()

        val recreated = Robolectric.buildActivity(ServiceActivity::class.java)
        recreated.get().sessionFactory = { _, onSnapshot, onState ->
            session.onSnapshot = onSnapshot
            session.onState = onState
            session
        }
        recreated.create().start().resume()
        try {
            val services = generateSequence { shadowOf(recreated.get()).nextStartedService }.toList()
            assertTrue("重建页面只观察，不启动后台", services.isEmpty())
            assertFalse(control(recreated.get(), R.id.service_automatic).isChecked)
        } finally {
            recreated.pause().stop().destroy()
        }
    }

    @Test
    fun navigationUsesRealTypesAndWifiFailureShowsResourceFeedback() {
        activity.findViewById<View>(R.id.service_account).performClick()
        assertEquals(
            VehicleAccountActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
        activity.findViewById<View>(R.id.service_diagnostics).performClick()
        assertEquals(
            DiagnosticActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        activity.findViewById<View>(R.id.service_open_wifi).performClick()
        assertEquals(
            activity.getString(R.string.service_wifi_unavailable),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun remoteButtonsSendHoursOnceAndStatusUsesReadOnlyFields() {
        activity.findViewById<View>(R.id.service_remote_12h).performClick()
        val twelve = drainServices().single()
        assertEquals("remote-authorize", twelve.action)
        assertEquals(12, twelve.getIntExtra("hours", -1))

        activity.findViewById<View>(R.id.service_remote_24h).performClick()
        val day = drainServices().single()
        assertEquals("remote-authorize", day.action)
        assertEquals(24, day.getIntExtra("hours", -1))

        activity.findViewById<View>(R.id.service_remote_disable).performClick()
        assertEquals("remote-disable", drainServices().single().action)

        val prefs = activity.getSharedPreferences("remote-access", Context.MODE_PRIVATE)
        val until = System.currentTimeMillis() / 1000 + 3600
        prefs
            .edit()
            .putLong("until", until)
            .putBoolean("paired", true)
            .putString("code", "")
            .commit()
        session.push(snapshot(remoteState = "connected"))
        assertTrue(text(R.id.service_remote_status).contains("已配对"))

        prefs
            .edit()
            .putBoolean("paired", false)
            .putString("code", "123456")
            .putLong("codeUntil", System.currentTimeMillis() / 1000 - 60)
            .commit()
        session.push(snapshot(remoteState = "waiting_code"))
        assertEquals(activity.getString(R.string.service_remote_code_expired), text(R.id.service_remote_status))

        prefs.edit().putString("code", "").commit()
        session.push(snapshot(remoteState = "tls_failed"))
        assertEquals(
            activity.getString(R.string.service_remote_state_tls_failed),
            text(R.id.service_remote_status),
        )
    }

    @Test
    fun pollingUpdatesOnlyForegroundAndStopsWithLifecycle() {
        session.push(snapshot())
        val prefs = activity.getSharedPreferences("remote-access", Context.MODE_PRIVATE)
        prefs
            .edit()
            .putLong("until", System.currentTimeMillis() / 1000 + 3600)
            .putString("code", "654321")
            .putLong("codeUntil", System.currentTimeMillis() / 1000 + 120)
            .commit()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
        assertTrue("前台 ticker 必须刷新验证码", text(R.id.service_remote_status).contains("654321"))

        controller.pause().stop()
        assertEquals(1, session.closes)
        prefs.edit().putString("code", "000000").commit()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2500))
        assertTrue("停止后 ticker 不得继续读取", !text(R.id.service_remote_status).contains("000000"))

        controller.start().resume()
        assertEquals("重新进入页面只重建一个 observer", 2, session.connects)
        assertTrue("页面打开与轮询都不自动授权", drainServices().none { it.action == "remote-authorize" })
    }
}
