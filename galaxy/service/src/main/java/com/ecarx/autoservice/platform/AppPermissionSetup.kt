package com.ecarx.autoservice.platform

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import org.json.JSONObject

/** 权限维护先用Binder读实际状态，只对缺项发Shell；健康轮次不启动保活命令或dumpsys子进程。 */
internal class AppPermissionSetup(private val context: Context) {
    fun repair(active: () -> Boolean): String {
        val results = mutableListOf<String>()
        val user = Process.myUid() / 100000
        fun command(value: String) {
            if (active()) RootServiceClient.request(context, 3, JSONObject().put("command", value).put("timeoutMillis", 5000), 7000)
        }
        for (name in listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.READ_LOGS")) {
            if (!active()) break
            if (context.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED)
                command("pm grant --user $user com.ecarx.autoservice $name")
            results += "${name.substringAfterLast('.')}=" + (context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED)
        }
        if(active()) {
            val component=android.content.ComponentName(context,com.ecarx.autoservice.media.MediaAccessListener::class.java)
            val manager=context.getSystemService(android.app.NotificationManager::class.java)
            if(!manager.isNotificationListenerAccessGranted(component))
                command("cmd notification allow_listener ${component.flattenToString()} $user")
            results += "media_session_access=${manager.isNotificationListenerAccessGranted(component)}"
        }
        val appOps = context.getSystemService(AppOpsManager::class.java)
        for ((operation, shellName) in listOf("android:run_in_background" to "RUN_IN_BACKGROUND", "android:run_any_in_background" to "RUN_ANY_IN_BACKGROUND")) {
            if (!active()) break
            fun allowed() = runCatching { appOps.unsafeCheckOpNoThrow(operation, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED }.getOrDefault(false)
            if (!allowed()) command("cmd appops set --user $user com.ecarx.autoservice $shellName allow")
            results += "$shellName=${allowed()}"
        }
        if (active()) {
            val power = context.getSystemService(PowerManager::class.java)
            if (!power.isIgnoringBatteryOptimizations(context.packageName)) command("dumpsys deviceidle whitelist +com.ecarx.autoservice")
            results += "deviceidle=${power.isIgnoringBatteryOptimizations(context.packageName)}"
        }
        return results.joinToString(" ")
    }
}
