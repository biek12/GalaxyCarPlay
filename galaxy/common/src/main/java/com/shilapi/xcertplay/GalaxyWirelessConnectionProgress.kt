package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.network.WirelessStartupFailure

/** 按v0.2.21实际公开状态显示步骤；不伪造旧分支独有的TCP/视频阶段，失败保留出错步骤。 */
internal class GalaxyWirelessConnectionProgress {
    private var step = 1

    fun restarting(context: Context): String {
        step = 1
        return context.getString(R.string.galaxy_wireless_step, step,
            context.getString(R.string.galaxy_wireless_releasing))
    }

    fun describe(context: Context, status: CarPlayStatus, detail: String): String {
        val next = when (status) {
            CarPlayStatus.DiscoveringMfi, CarPlayStatus.WaitingForMfi,
            CarPlayStatus.RequestingMfiPermission, CarPlayStatus.MfiReady,
            CarPlayStatus.StartingHotspot, is CarPlayStatus.HotspotReady,
            CarPlayStatus.AttachingNetwork, CarPlayStatus.WaitingForPairedIphone -> 1
            CarPlayStatus.ConnectingBluetooth -> 2
            CarPlayStatus.RunningWireless -> 3
            CarPlayStatus.ConnectingControl, CarPlayStatus.RunningControl -> 4
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> 5
            is CarPlayStatus.Failed -> step
            else -> return detail
        }
        step = next
        val text = when {
            status == CarPlayStatus.RunningWireless -> context.getString(R.string.galaxy_wireless_authenticating)
            status is CarPlayStatus.Failed && status.startupFailure == WirelessStartupFailure.FIRST_TCP_TIMEOUT ->
                context.getString(R.string.galaxy_wireless_network_timeout)
            status is CarPlayStatus.Failed && status.startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION &&
                status.message.startsWith("Wi-Fi Direct") && (status.message.contains("vehicle region") || status.message.contains("channel policy")) ->
                context.getString(R.string.galaxy_p2p_region_failure)
            status is CarPlayStatus.Failed && status.startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION &&
                status.message.startsWith("Wi-Fi Direct") -> context.getString(R.string.galaxy_p2p_configuration_changed)
            else -> detail
        }
        return context.getString(R.string.galaxy_wireless_step, step, text)
    }
}
