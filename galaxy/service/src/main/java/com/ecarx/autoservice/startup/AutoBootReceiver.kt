package com.ecarx.autoservice.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 广播只调度一次启动；Root/TLS等待始终在服务worker，包更新与开机共用去重。 */
class AutoBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        runCatching { AutoStartup.start(context, "automatic") }
    }
}
