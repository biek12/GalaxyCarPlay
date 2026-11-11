package com.ecarx.autoservice.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings

/** 与 CarPlay 客户端 Wi-Fi 入口使用同一 action；启动结果不代表 Wi-Fi 已开启。 */
object NativeWifiSettings {
    fun open(activity: Activity): Boolean = try {
        activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
