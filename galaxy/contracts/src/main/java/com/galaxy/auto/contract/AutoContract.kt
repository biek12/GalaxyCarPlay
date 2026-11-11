package com.galaxy.auto.contract

import android.os.Bundle

/** 公共契约不依赖任何CarPlay/车型实现。v1新增字段可忽略，改变语义必须升级版本。 */
object AutoContract {
    const val VERSION = 6
    const val SERVICE_PACKAGE = "com.ecarx.autoservice"
    const val SERVICE_CLASS = "com.ecarx.autoservice.AutoService"
    const val CLUSTER_PACKAGE = "com.galaxy.autocluster"
    const val ACTION = "com.galaxy.auto.BIND_PLATFORM"
    const val MAP = "carplay"
    const val VISUAL_MUSIC = "cluster-music"
    const val THEME = "cluster"
    const val OBSERVER = "observer"
    const val PREVIEW = "cluster-preview"
    /** 原厂元素独占租约，与遮罩Surface及旧projection开关独立。 */
    const val ELEMENTS = "cluster-elements"
    const val WIDTH = 1920
    const val HEIGHT = 532
    const val MAP_HEIGHT = 536
    const val MAX_TEXT = 1024
    const val MAX_LYRICS = 120
    /** v3按需车辆信号订阅：键名与AutoService车型目录的语义别名一致。 */
    const val SIGNAL_DISPLAY_SPEED = "displaySpeed"
    const val SIGNAL_GEAR = "gear"
    /** 最小集与全部扩展集目前合计超过32；有界放宽到64，仍拒绝超量请求。 */
    const val MAX_FEED_KEYS = 64
    private val FEED_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    fun roleValid(role: String) = role in setOf(MAP, THEME, OBSERVER, PREVIEW, ELEMENTS, VISUAL_MUSIC)
    fun producesOutput(role: String) = role == MAP || role == THEME
    fun surfaceKind(role: String, token: Long) = "$role@$token"
    /** 空数组表示取消订阅；键有界且只用目录别名格式，避免把任意字符串送进车型查表。 */
    fun feedKeysValid(keys: Array<String>?): Boolean =
        keys != null && keys.size <= MAX_FEED_KEYS && keys.all { FEED_KEY.matches(it) }
    /**
     * v4订阅节拍。间隔是“最小推送间隔”，同时决定采集端该键的读取周期：
     * 没有人订阅的键不读取，所以这个值直接决定CPU成本，不能给成0或负数。
     */
    const val MIN_FEED_INTERVAL_MS = 100
    const val MAX_FEED_INTERVAL_MS = 10_000
    const val DEFAULT_FEED_INTERVAL_MS = 250
    fun feedIntervalValid(value: Int) = value in MIN_FEED_INTERVAL_MS..MAX_FEED_INTERVAL_MS
    fun copyText(source: Bundle, target: Bundle, key: String, limit: Int = MAX_TEXT) {
        source.getString(key)?.let { target.putString(key, it.take(limit)) }
    }
    fun sanitizeMedia(source: Bundle) = Bundle().apply {
        for (key in listOf("title", "artist", "album", "track", "sourceKind", "sourceApp", "sourcePackage", "sourceTrackId", "sourceGeneration")) copyText(source, this, key)
        putBoolean("playing", source.getBoolean("playing"))
        putLong("sampledAt", source.getLong("sampledAt", 0).coerceAtLeast(0))
        putLong("elapsed", source.getLong("elapsed", -1).coerceIn(-1, 86_400_000))
        putLong("duration", source.getLong("duration", -1).coerceIn(-1, 86_400_000))
        val lines = source.getStringArrayList("lyrics")?.take(MAX_LYRICS)?.map { it.take(512) }
        if (lines != null) putStringArrayList("lyrics", ArrayList(lines))
        source.getLongArray("lyricTimes")?.takeIf { it.size == lines?.size && it.all{time->time in 0..86_400_000} && it.asSequence().zipWithNext().all{(a,b)->a<=b} }?.let { putLongArray("lyricTimes", it.clone()) }
    }
    fun sanitizeNavigation(source: Bundle) = Bundle().apply {
        for (key in listOf("road", "instruction", "distance", "arrival", "remaining", "remainingDistance")) copyText(source, this, key)
        putInt("maneuver", source.getInt("maneuver", -1))
        putInt("roundaboutExit",source.getInt("roundaboutExit",-1).coerceIn(-1,99))
        val angles=source.getString("laneAngles").orEmpty().split(',').mapNotNull{it.toIntOrNull()}.take(8)
        val recommended=source.getString("laneRecommended").orEmpty().split(',').mapNotNull{it.toIntOrNull()}.take(8)
        if(angles.isNotEmpty()&&angles.size==recommended.size&&angles.all{it in -180..180}&&recommended.all{it in 0..1}){
            putString("laneAngles",angles.joinToString(","));putString("laneRecommended",recommended.joinToString(","))
        }
    }
}
