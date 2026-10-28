package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.telecom.PhoneAudioRoute

/** 产品入口由产品清单选择；不改上游BootReceiver，也不允许撤回协议后自动连接。 */
class GalaxyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !L7Agreement.canUse(context)) return
        PhoneAudioRoute.attach(context)
        val enabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, enabled)
        if (!enabled) return
        val launch = Intent(context, GalaxySettingsActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(launch) }
            .onSuccess { StartupDiagnosticSnapshot.launchResult(context) }
            .onFailure { error ->
                if (error is RuntimeException) StartupDiagnosticSnapshot.launchResult(context, error)
            }
    }
}

/** 只响应用户选定手机；沿上游去重窗口，不把地址或广播载荷送入日志。 */
class GalaxyPhoneBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!L7Agreement.canUse(context) || intent.action != BluetoothDevice.ACTION_ACL_CONNECTED ||
            !DiPlayPreferences.connectOnPhoneBluetooth(context) || CarPlayBackgroundSession.hasSession()) return
        val selected = DiPlayPreferences.phoneAddress(context) ?: return
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        if (!runCatching { device.address.equals(selected, ignoreCase = true) }.getOrDefault(false)) return
        val preferences = context.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val last = preferences.getLong("last_launch_elapsed", 0)
        if (preferences.contains("last_launch_elapsed") && now >= last && now - last < 15_000) return
        if (!preferences.edit().putLong("last_launch_elapsed", now).commit()) return
        PhoneWakeDiagnostics.record(context, "matched")
        val launch = Intent(context, GalaxySettingsActivity::class.java)
            .putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(launch) }
            .onSuccess { PhoneWakeDiagnostics.record(context, "startActivity-returned") }
            .onFailure { PhoneWakeDiagnostics.record(context, "failed", it) }
    }
}
