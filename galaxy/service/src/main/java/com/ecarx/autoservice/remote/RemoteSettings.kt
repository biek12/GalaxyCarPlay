package com.ecarx.autoservice.remote

import android.content.Context
import java.security.SecureRandom

/** 私有身份与授权窗口跨APK更新保存；不包含车辆/手机标识，不在日志中输出凭据。 */
internal class RemoteSettings(context: Context) {
    private val prefs = context.getSharedPreferences("remote-access", 0)
    val origin: String get() = prefs.getString("origin", com.ecarx.autoservice.BuildConfig.GALAXY_ORIGIN)!!
    val until: Long get() = prefs.getLong("until", 0)
    val enabled: Boolean get() = until > System.currentTimeMillis() / 1000
    val id: String get() = identity("id")
    val secret: String get() = identity("secret")
    val grant: String get() = prefs.getString("grant", "")!!
    val code: String get() = prefs.getString("code", "")!!
    val codeUntil: Long get() = prefs.getLong("codeUntil", 0)
    val paired: Boolean get() = prefs.getBoolean("paired", false)

    private fun identity(key: String): String = synchronized(guard) {
        prefs.getString(key, null) ?: random().also {
            check(prefs.edit().putString(key, it).commit())
        }
    }

    fun authorize(hours: Int) = synchronized(guard) {
        require(hours == 12 || hours == 24)
        check(prefs.edit()
            .putLong("until", System.currentTimeMillis() / 1000 + hours * 3600)
            .putString("grant", random())
            .putString("code", "")
            .putBoolean("paired", false)
            .commit())
    }

    fun disable() = synchronized(guard) {
        check(prefs.edit().putLong("until", 0).putString("code", "").putBoolean("paired", false).commit())
    }

    fun observed(expected: String, code: String, paired: Boolean, codeUntil: Long) = synchronized(guard) {
        if (grant == expected) {
            prefs.edit().putString("code", code).putBoolean("paired", paired)
                .putLong("codeUntil", codeUntil).apply()
        }
    }

    private fun random() = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private val guard = Any()
    }
}
