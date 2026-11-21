package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.host.databinding.ViewL7HomePanelBinding
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** 首页只承担连接入口；配置和认证仍进入统一设置页。 */
internal class L7HomePanel(
    context: Context,
    onQuickConnect: () -> Unit,
    onWireless: () -> Unit,
    onUsb: () -> Unit,
    onSettings: () -> Unit,
) : LinearLayout(context) {
    private val binding: ViewL7HomePanelBinding
    internal val quick get() = binding.homeQuick
    internal val wireless get() = binding.homeWireless
    internal val usb get() = binding.homeUsb
    internal val settings get() = binding.homeSettings
    private var wirelessPrimary = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        binding = ViewL7HomePanelBinding.inflate(LayoutInflater.from(context), this)
        L7Ui.text(binding.homeTitle, R.color.product_ui_text)
        L7Ui.text(binding.homeHelp, R.color.product_ui_muted)
        L7Ui.text(binding.homeStatus, R.color.product_ui_muted)
        L7Components.bindActionButton(
            quick,
            context.getString(R.string.l7_entry_connect),
            primary = true,
            click = onQuickConnect,
        )
        L7Components.bindActionButton(wireless, context.getString(R.string.l7_start_wireless), click = onWireless)
        L7Components.bindActionButton(usb, context.getString(R.string.l7_home_usb), click = onUsb)
        L7Components.bindActionButton(settings, context.getString(R.string.settings), click = onSettings)
    }

    /** 按配置、会话和错误刷新主按钮优先级、文案和禁用状态。 */
    fun update(configured: Boolean, running: Boolean, connected: Boolean, pending: Boolean, error: String?) {
        quick.visibility = if (configured || running || pending) View.VISIBLE else View.GONE
        val promoteWireless = quick.visibility == View.GONE
        if (wirelessPrimary != promoteWireless) {
            wirelessPrimary = promoteWireless
            L7Ui.button(wireless, wirelessPrimary)
        }
        val title =
            when {
                pending -> R.string.l7_preparing
                connected -> R.string.l7_resume_projection
                running -> R.string.l7_view_connection
                else -> R.string.l7_entry_connect
            }
        if (quick.text.toString() != context.getString(title)) {
            quick.setText(title)
            L7Icons.decorate(quick)
        }
        val enabled = !pending && (running || error == null)
        if (quick.isEnabled != enabled) {
            quick.isEnabled = enabled
            L7Ui.refresh(quick)
        }
        usb.isEnabled = !running && !pending
        val message =
            error ?: context.getString(
                when {
                    connected -> R.string.l7_session_connected_hint
                    running -> R.string.l7_session_waiting_hint
                    configured -> R.string.ready_when_you_are
                    else -> R.string.l7_home_first_use
                },
            )
        if (binding.homeStatus.text.toString() != message) binding.homeStatus.text = message
    }

    companion object {
        /** 无线需要手机和热点资料；曾经明确选择 USB 时可以直接再次发起。 */
        fun configured(context: Context): Boolean {
            if (!AirPlayPersistence.loadWirelessEnabled(context)) return true
            return DiPlayPreferences.phoneAddress(context) != null &&
                ManualHotspotValidation.error(
                    AirPlayPersistence.loadManualHotspotSsid(context),
                    AirPlayPersistence.loadManualHotspotPassphrase(context),
                ) == null
        }
    }
}
