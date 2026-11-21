package com.shilapi.xcertplay.settings

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.GalaxyProfileFieldsView
import com.shilapi.xcertplay.GalaxyWirelessSettings
import com.shilapi.xcertplay.L7Components
import com.shilapi.xcertplay.L7HotspotActions
import com.shilapi.xcertplay.L7HotspotSettings
import com.shilapi.xcertplay.L7HotspotTask
import com.shilapi.xcertplay.L7SettingRow
import com.shilapi.xcertplay.L7Ui
import com.shilapi.xcertplay.L7WirelessPrerequisiteView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.host.databinding.PageGalaxyConnectionBinding
import com.shilapi.xcertplay.host.databinding.PageGalaxyWirelessBinding
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * 连接选择和无线页只负责把现有控件放进固定槽位并回填文案。
 *
 * 连接、重连和停止由 [GalaxyConnectionActions] 执行。无线模式确认后的保存、取消旧 gate、
 * 刷新和提示由调用方回调完成，本页不发起连接。
 */
internal object GalaxyConnectionPage {
    /**
     * 无线页装好后交还给宿主的现有控件。
     * 宿主继续按原顺序释放热点、无线设置和前置视图。
     */
    class WirelessBindings(
        val prerequisites: L7WirelessPrerequisiteView,
        val wireless: GalaxyWirelessSettings,
        val hotspot: L7HotspotSettings?,
        val connect: Button,
        val disconnect: Button,
    )

    /**
     * 装入无线、USB、认证、重连和自动连接。
     * 返回重连行，供状态刷新启用或禁用。
     */
    fun bindChoices(
        activity: Activity,
        parent: LinearLayout,
        onWireless: () -> Unit,
        onUsb: () -> Unit,
        onAuth: () -> Unit,
        onReconnect: () -> Unit,
    ): L7SettingRow {
        val binding = PageGalaxyConnectionBinding.inflate(activity.layoutInflater, parent, false)
        L7Components.bindActionRow(binding.connectionWireless, onWireless)
        L7Components.bindActionRow(binding.connectionUsb, onUsb)
        L7Components.bindActionRow(binding.connectionAuth, onAuth)
        L7Components.bindActionRow(binding.connectionReconnect, onReconnect)
        GalaxyProfileFieldsView.add(activity, binding.connectionAutoCard, R.string.automatic_connection)
        parent.addView(binding.root)
        return binding.connectionReconnect
    }

    /**
     * 按 MANUAL 或 Wi-Fi Direct 把现有前置、无线设置和热点控件装进固定槽。
     * 控件创建顺序与原来一致：前置、方式、直连或热点、加入说明、动作按钮。
     */
    fun bindWireless(
        activity: Activity,
        parent: LinearLayout,
        canEdit: () -> Boolean,
        onModeConfirmed: () -> Unit,
        choosePhone: () -> Unit,
        onEditHotspot: () -> Unit,
        onConnect: () -> Unit,
        onOpenProjection: () -> Unit,
        onDisconnect: () -> Unit,
        hotspotTask: L7HotspotTask,
        hotspotActions: L7HotspotActions,
    ): WirelessBindings {
        val binding = PageGalaxyWirelessBinding.inflate(activity.layoutInflater, parent, false)
        val direct = AirPlayPersistence.loadWirelessHotspotMode(activity) == WirelessHotspotMode.WIFI_P2P
        binding.wirelessModeTitle.setText(if (direct) R.string.wifi_direct else R.string.l7_wireless_steps)
        binding.wirelessJoinTitle.setText(if (direct) R.string.l7_wireless_join_iphone else R.string.l7_wireless_step3)
        binding.wirelessJoinRow.setTitle(activity.getString(R.string.l7_wireless_join_iphone))
        binding.wirelessJoinRow.setDescription(
            activity.getString(if (direct) R.string.wifi_direct_phone_hint else R.string.l7_wireless_phone_hint),
        )
        L7Ui.text(binding.wirelessModeTitle, R.color.product_ui_muted)
        L7Ui.text(binding.wirelessModeHint, R.color.product_ui_muted)
        L7Ui.text(binding.wirelessJoinTitle, R.color.product_ui_muted)
        val prerequisites = L7WirelessPrerequisiteView(activity, binding.wirelessPrerequisiteSlot, choosePhone)
        val wireless = GalaxyWirelessSettings(activity, binding.wirelessSettingsSlot, canEdit, onModeConfirmed)
        val hotspot =
            if (direct) {
                wireless.addDirect(binding.wirelessLinkSlot)
                null
            } else {
                L7HotspotSettings(activity, binding.wirelessLinkSlot, hotspotTask, hotspotActions, onEditHotspot)
            }
        L7Components.bindActionButton(
            binding.wirelessConnect,
            activity.getString(R.string.l7_start_wireless),
            primary = true,
        ) {
            if (CarPlayBackgroundSession.hasSession()) onOpenProjection() else onConnect()
        }
        L7Components.bindActionButton(
            binding.wirelessDisconnect,
            activity.getString(R.string.disconnect),
            primary = false,
            click = onDisconnect,
        )
        binding.wirelessDisconnect.visibility = View.GONE
        parent.addView(binding.root)
        return WirelessBindings(prerequisites, wireless, hotspot, binding.wirelessConnect, binding.wirelessDisconnect)
    }
}
