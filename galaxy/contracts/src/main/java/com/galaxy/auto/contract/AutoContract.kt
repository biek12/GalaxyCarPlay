package com.galaxy.auto.contract

import android.os.Bundle

/** 公共契约不依赖任何CarPlay/车型实现。v1新增字段可忽略，改变语义必须升级版本。 */
object AutoContract {
    const val VERSION = 2
    const val SERVICE_PACKAGE = "com.ecarx.autoservice"
    const val SERVICE_CLASS = "com.ecarx.autoservice.AutoService"
    const val CLUSTER_PACKAGE = "com.galaxy.autocluster"
    const val ACTION = "com.galaxy.auto.BIND_PLATFORM"
    const val MAP = "carplay"
    const val THEME = "cluster"
    const val OBSERVER = "observer"
    const val PREVIEW = "cluster-preview"
    const val WIDTH = 1920
    const val HEIGHT = 532
    const val MAP_HEIGHT = 536
    const val MAX_TEXT = 1024
    const val MAX_LYRICS = 120
    fun roleValid(role: String) = role in setOf(MAP, THEME, OBSERVER, PREVIEW)
    fun producesOutput(role: String) = role == MAP || role == THEME
    fun surfaceKind(role: String, token: Long) = "$role@$token"
    fun copyText(source: Bundle, target: Bundle, key: String, limit: Int = MAX_TEXT) {
        source.getString(key)?.let { target.putString(key, it.take(limit)) }
    }
    fun sanitizeMedia(source: Bundle) = Bundle().apply {
        for (key in listOf("title", "artist", "album", "track")) copyText(source, this, key)
        putBoolean("playing", source.getBoolean("playing"))
        putLong("sampledAt", source.getLong("sampledAt", 0).coerceAtLeast(0))
        putLong("elapsed", source.getLong("elapsed", -1).coerceIn(-1, 86_400_000))
        putLong("duration", source.getLong("duration", -1).coerceIn(-1, 86_400_000))
        val lines = source.getStringArrayList("lyrics")?.take(MAX_LYRICS)?.map { it.take(512) }
        if (lines != null) putStringArrayList("lyrics", ArrayList(lines))
        source.getLongArray("lyricTimes")?.takeIf { it.size == lines?.size }?.let { putLongArray("lyricTimes", it.clone()) }
    }
    fun sanitizeNavigation(source: Bundle) = Bundle().apply {
        for (key in listOf("road", "instruction", "distance", "arrival", "remaining", "remainingDistance")) copyText(source, this, key)
        putInt("maneuver", source.getInt("maneuver", -1))
        putInt("roundaboutExit",source.getInt("roundaboutExit",-1).coerceIn(-1,99))
    }
}
