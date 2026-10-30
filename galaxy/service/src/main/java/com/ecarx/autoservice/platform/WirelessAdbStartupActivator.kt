package com.ecarx.autoservice.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import java.util.concurrent.TimeUnit

/** Re-enables only Android's ADB switches during an explicit startup attempt. */
internal class WirelessAdbStartupActivator(context: Context) {
    private val app = context.applicationContext

    fun enable(active: () -> Boolean = { true }): String {
        if (!active()) return "status=cancelled"
        if (app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED) {
            return "status=permission_missing; permission=${Manifest.permission.WRITE_SECURE_SETTINGS}"
        }

        val resolver = app.contentResolver
        val adbWrite = runCatching {
            Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1 ||
                Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
        }.getOrDefault(false)
        if (!active()) return "status=cancelled"
        val wifiWrite = runCatching {
            Settings.Global.getInt(resolver, WIFI_ADB_SETTING, 0) == 1 ||
                Settings.Global.putInt(resolver, WIFI_ADB_SETTING, 1)
        }.getOrDefault(false)

        var adbEnabled = false
        var wifiEnabled = false
        var tlsPort = ""
        repeat(STARTUP_CHECKS) { attempt ->
            if (!active()) return "status=cancelled"
            adbEnabled = readSetting(Settings.Global.ADB_ENABLED)
            wifiEnabled = readSetting(WIFI_ADB_SETTING)
            tlsPort = readProperty(TLS_PORT_PROPERTY)
            if (adbEnabled && wifiEnabled && (tlsPort.toIntOrNull() ?: 0) > 0) {
                return "status=enabled; adb_enabled=1; adb_wifi_enabled=1; tls_port=$tlsPort; " +
                    "write_results=$adbWrite,$wifiWrite"
            }
            if (attempt < STARTUP_CHECKS - 1) Thread.sleep(STARTUP_CHECK_INTERVAL_MS)
        }

        return "status=not_ready; adb_enabled=${adbEnabled.toIntFlag()}; " +
            "adb_wifi_enabled=${wifiEnabled.toIntFlag()}; tls_port=${tlsPort.ifBlank { "empty" }}; " +
            "write_results=$adbWrite,$wifiWrite"
    }

    private fun readSetting(name: String): Boolean = runCatching {
        Settings.Global.getInt(app.contentResolver, name, 0) == 1
    }.getOrDefault(false)

    private fun readProperty(name: String): String = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
        if (!process.waitFor(PROPERTY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            ""
        } else if (process.exitValue() == 0) process.inputStream.bufferedReader().use { it.readText().trim() } else ""
    }.getOrDefault("")

    private fun Boolean.toIntFlag(): Int = if (this) 1 else 0

    private companion object {
        const val WIFI_ADB_SETTING = "adb_wifi_enabled"
        const val TLS_PORT_PROPERTY = "service.adb.tls.port"
        const val STARTUP_CHECKS = 20
        const val STARTUP_CHECK_INTERVAL_MS = 500L
        const val PROPERTY_TIMEOUT_MS = 1_000L
    }
}
