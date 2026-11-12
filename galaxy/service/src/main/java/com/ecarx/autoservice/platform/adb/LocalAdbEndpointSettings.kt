package com.ecarx.autoservice.platform.adb

import android.content.Context

/**
 * 用应用私有偏好保存本机 ADB 端点。
 *
 * 存储名保持 `platform`。任意线程可读写；`save` 使用 `apply`，不合规的值直接拒绝且不写。
 */
internal class LocalAdbEndpointSettings(
    context: Context,
) : AdbConnectionSettingsStore {
    private val preferences = context.applicationContext.getSharedPreferences("platform", Context.MODE_PRIVATE)

    /** 缺键时主机为 `127.0.0.1`，端口为 5555，直连为 false。 */
    override fun read(): AdbConnectionSettings =
        AdbConnectionSettings(
            preferences.getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1",
            preferences.getInt(KEY_PORT, 5555),
            preferences.getBoolean(KEY_DIRECT, false),
        )

    /**
     * 先按 [AdbConnectionSettings.valid] 拒绝，再写入主机、端口和直连标记。
     * 主机会去掉首尾空白。返回 false 时偏好保持原样。
     */
    override fun save(settings: AdbConnectionSettings): Boolean {
        if (!settings.valid()) return false
        preferences
            .edit()
            .putString(KEY_HOST, settings.host.trim())
            .putInt(KEY_PORT, settings.port)
            .putBoolean(KEY_DIRECT, settings.directConnect)
            .apply()
        return true
    }

    private companion object {
        /** 历史偏好名。实际存储仍是构造时的 `platform`，不能改去使用这个常量。 */
        @Suppress("unused")
        const val PREFERENCES = "adb_connection_settings"
        const val KEY_HOST = "adbHost"
        const val KEY_PORT = "adbPort"
        const val KEY_DIRECT = "adbDirect"
    }
}
