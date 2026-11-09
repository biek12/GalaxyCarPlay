package com.galaxy.autorecord.core

import android.content.Context
import android.util.Log

/**
 * AutoRecord 设置模型。字段与键名对应 docs/AutoRecord页面功能与设置规格-20261103.md §7。
 *
 * 纪律：读取非法值回落到默认值并记一次诊断日志（不把非法值当正常），
 * 保存立即落盘；影响编码/写盘的字段由录制侧在"下一个片段"生效，界面不得中断当前片段。
 */
data class RecordSettings(
    /** null＝内置存储；否则为卷 UUID（不存绝对路径）。 */
    val storageVolumeUuid: String? = null,
    /** 0＝自动（32 GB 与介质可用容量 50% 取小），>0＝显式配额字节。 */
    val quotaBytes: Long = 0L,
    val rotate: Boolean = true,
    val minFreeBytes: Long = 2L * 1024 * 1024 * 1024,
    val segmentSeconds: Int = 60,
    /** "video/avc" | "video/hevc" */
    val codecMime: String = CODEC_H264,
    /** "hardware" | "software" */
    val encoderImplementation: String = IMPL_HARDWARE,
    /** 0＝跟随摄像头原生尺寸。 */
    val requestedHeight: Int = 0,
    val fps: Int = 25,
    /** "auto" | "low" | "mid" | "high" */
    val bitrateMode: String = BITRATE_AUTO,
    val gopSeconds: Int = 1,
    val audio: Boolean = false,
    val autoOnBoot: Boolean = false,
    val autoOnLaunch: Boolean = false,
    val turnAssist: Boolean = false,
    val flatView: Boolean = false,
    val sentinelEnabled: Boolean = false,
    val recoverAfterCrash: Boolean = true,
    val background: Boolean = true,
    val screenOff: Boolean = true,
    val protectDeleting: Boolean = true,
    val vehicleIntervalMs: Int = 100,
    val extraVehicleGroups: Set<String> = emptySet(),
    val brakeEnter: Int = 5,
    val brakeExit: Int = 3,
    val accelEnter: Int = 20,
    val accelExit: Int = 10,
    val debounceMs: Int = 200,
    val minEventMs: Int = 300,
    val mergeMs: Int = 1000,
    /** 含事件片段不参与循环覆盖（v1.1 S-EVT-04）。 */
    val eventProtect: Boolean = true,
    /** "grid" | "single" */
    val playbackLayout: String = PLAYBACK_GRID,
    val playbackSpeed: Float = 1f,
    /** "stop" | "next" */
    val playbackOnEnd: String = PLAYBACK_STOP,
    val trackExpanded: Boolean = true,
    /** "system" | "light" | "dark" */
    val themeMode: String = THEME_SYSTEM,
) {
    /** 配额实际值：自动档取 32 GB 与介质可用容量一半的较小者。 */
    fun effectiveQuotaBytes(volumeTotalBytes: Long): Long {
        if (quotaBytes > 0L) return quotaBytes
        val half = if (volumeTotalBytes > 0L) volumeTotalBytes / 2 else AUTO_QUOTA_BYTES
        return minOf(AUTO_QUOTA_BYTES, half).coerceAtLeast(0L)
    }

    /** 事件规则稳定标识；改动任一阈值即变化，历史片段按记录值解释，不回算。 */
    val eventRulesVersion: String
        get() = "brake:$brakeEnter/$brakeExit accel:$accelEnter/$accelExit " +
            "debounce:${debounceMs}ms min:${minEventMs}ms merge:${mergeMs}ms"

    companion object {
        const val CODEC_H264 = "video/avc"
        const val CODEC_H265 = "video/hevc"
        const val IMPL_HARDWARE = "hardware"
        const val IMPL_SOFTWARE = "software"
        const val BITRATE_AUTO = "auto"
        const val BITRATE_LOW = "low"
        const val BITRATE_MID = "mid"
        const val BITRATE_HIGH = "high"
        const val PLAYBACK_GRID = "grid"
        const val PLAYBACK_SINGLE = "single"
        const val PLAYBACK_STOP = "stop"
        const val PLAYBACK_NEXT = "next"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        /** 自动配额上限 32 GB；介质一半更小时取介质一半。 */
        const val AUTO_QUOTA_BYTES = 32L * 1024 * 1024 * 1024
        const val MIN_QUOTA_BYTES = 1L * 1024 * 1024 * 1024

        val SEGMENT_OPTIONS = listOf(60, 180, 300)
        val QUOTA_OPTIONS_GB = listOf(8, 16, 32, 64)
        val MIN_FREE_OPTIONS_GB = listOf(1, 2, 5)
        val VEHICLE_INTERVAL_OPTIONS = listOf(100, 250, 500, 1000)
        val GOP_OPTIONS = listOf(1, 2)
        val FPS_OPTIONS = listOf(15, 20, 25, 30)
        val SPEED_OPTIONS = listOf(0.5f, 1f, 2f)
    }
}

/** 设置持久化。键名沿用规格 §7 建议键；非法值回落默认并记一次警告。 */
interface SettingsStore {
    fun load(): RecordSettings
    fun save(settings: RecordSettings)
}

class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): RecordSettings {
        val d = RecordSettings()
        fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
        fun int(key: String, def: Int) = prefs.getInt(key, def)
        fun long(key: String, def: Long) = prefs.getLong(key, def)
        fun str(key: String, def: String) = prefs.getString(key, def) ?: def

        val codec = str(KEY_CODEC, d.codecMime).takeIf { it == RecordSettings.CODEC_H264 || it == RecordSettings.CODEC_H265 }
            ?: fallback(KEY_CODEC, d.codecMime)
        val impl = str(KEY_IMPL, d.encoderImplementation)
            .takeIf { it == RecordSettings.IMPL_HARDWARE || it == RecordSettings.IMPL_SOFTWARE }
            ?: fallback(KEY_IMPL, d.encoderImplementation)
        val speedStored = prefs.getFloat(KEY_PLAYBACK_SPEED, d.playbackSpeed)
        val speed = if (speedStored in RecordSettings.SPEED_OPTIONS) speedStored else fallback(KEY_PLAYBACK_SPEED, d.playbackSpeed)

        return RecordSettings(
            storageVolumeUuid = prefs.getString(KEY_STORAGE_UUID, null),
            quotaBytes = long(KEY_QUOTA, d.quotaBytes).coerceAtLeast(0L),
            rotate = bool(KEY_ROTATE, d.rotate),
            minFreeBytes = long(KEY_MIN_FREE, d.minFreeBytes).coerceAtLeast(0L),
            segmentSeconds = int(KEY_SEGMENT, d.segmentSeconds)
                .takeIf { it in RecordSettings.SEGMENT_OPTIONS } ?: fallback(KEY_SEGMENT, d.segmentSeconds),
            codecMime = codec,
            encoderImplementation = impl,
            requestedHeight = int(KEY_HEIGHT, d.requestedHeight).coerceAtLeast(0),
            fps = int(KEY_FPS, d.fps).takeIf { it in RecordSettings.FPS_OPTIONS } ?: fallback(KEY_FPS, d.fps),
            bitrateMode = str(KEY_BITRATE, d.bitrateMode).takeIf {
                it in setOf(RecordSettings.BITRATE_AUTO, RecordSettings.BITRATE_LOW, RecordSettings.BITRATE_MID, RecordSettings.BITRATE_HIGH)
            } ?: fallback(KEY_BITRATE, d.bitrateMode),
            gopSeconds = int(KEY_GOP, d.gopSeconds).takeIf { it in RecordSettings.GOP_OPTIONS } ?: fallback(KEY_GOP, d.gopSeconds),
            audio = bool(KEY_AUDIO, d.audio),
            autoOnBoot = bool(KEY_AUTO_BOOT, d.autoOnBoot),
            autoOnLaunch = bool(KEY_AUTO_LAUNCH, d.autoOnLaunch),
            turnAssist = bool("assist.turn", d.turnAssist),
            flatView = bool("view.flat",d.flatView),
            sentinelEnabled = bool("sentinel.enabled",d.sentinelEnabled),
            recoverAfterCrash = bool(KEY_RECOVER, d.recoverAfterCrash),
            background = bool(KEY_BACKGROUND, d.background),
            screenOff = bool(KEY_SCREEN_OFF, d.screenOff),
            protectDeleting = bool(KEY_PROTECT_DELETE, d.protectDeleting),
            vehicleIntervalMs = int(KEY_VEHICLE_INTERVAL, d.vehicleIntervalMs)
                .takeIf { it in RecordSettings.VEHICLE_INTERVAL_OPTIONS } ?: fallback(KEY_VEHICLE_INTERVAL, d.vehicleIntervalMs),
            extraVehicleGroups = prefs.getStringSet(KEY_VEHICLE_EXTRA, emptySet())?.toSet() ?: emptySet(),
            brakeEnter = int(KEY_BRAKE_ENTER, d.brakeEnter).coerceIn(1, 100),
            brakeExit = int(KEY_BRAKE_EXIT, d.brakeExit).coerceIn(0, 100),
            accelEnter = int(KEY_ACCEL_ENTER, d.accelEnter).coerceIn(1, 100),
            accelExit = int(KEY_ACCEL_EXIT, d.accelExit).coerceIn(0, 100),
            debounceMs = int(KEY_DEBOUNCE, d.debounceMs).coerceIn(0, 2000),
            minEventMs = int(KEY_MIN_EVENT, d.minEventMs).coerceIn(0, 5000),
            mergeMs = int(KEY_MERGE, d.mergeMs).coerceIn(0, 10000),
            eventProtect = bool(KEY_EVENT_PROTECT, d.eventProtect),
            playbackLayout = str(KEY_PLAYBACK_LAYOUT, d.playbackLayout)
                .takeIf { it == RecordSettings.PLAYBACK_GRID || it == RecordSettings.PLAYBACK_SINGLE }
                ?: fallback(KEY_PLAYBACK_LAYOUT, d.playbackLayout),
            playbackSpeed = speed,
            playbackOnEnd = str(KEY_PLAYBACK_ON_END, d.playbackOnEnd)
                .takeIf { it == RecordSettings.PLAYBACK_STOP || it == RecordSettings.PLAYBACK_NEXT }
                ?: fallback(KEY_PLAYBACK_ON_END, d.playbackOnEnd),
            trackExpanded = bool(KEY_TRACK_EXPANDED, d.trackExpanded),
            themeMode = str(KEY_THEME, d.themeMode)
                .takeIf { it in setOf(RecordSettings.THEME_SYSTEM, RecordSettings.THEME_LIGHT, RecordSettings.THEME_DARK) }
                ?: fallback(KEY_THEME, d.themeMode),
        )
    }

    override fun save(settings: RecordSettings) {
        prefs.edit()
            .putString(KEY_STORAGE_UUID, settings.storageVolumeUuid)
            .putLong(KEY_QUOTA, settings.quotaBytes)
            .putBoolean(KEY_ROTATE, settings.rotate)
            .putLong(KEY_MIN_FREE, settings.minFreeBytes)
            .putInt(KEY_SEGMENT, settings.segmentSeconds)
            .putString(KEY_CODEC, settings.codecMime)
            .putString(KEY_IMPL, settings.encoderImplementation)
            .putInt(KEY_HEIGHT, settings.requestedHeight)
            .putInt(KEY_FPS, settings.fps)
            .putString(KEY_BITRATE, settings.bitrateMode)
            .putInt(KEY_GOP, settings.gopSeconds)
            .putBoolean(KEY_AUDIO, settings.audio)
            .putBoolean(KEY_AUTO_BOOT, settings.autoOnBoot)
            .putBoolean(KEY_AUTO_LAUNCH, settings.autoOnLaunch)
            .putBoolean("assist.turn", settings.turnAssist)
            .putBoolean("view.flat",settings.flatView)
            .putBoolean("sentinel.enabled",settings.sentinelEnabled)
            .putBoolean(KEY_RECOVER, settings.recoverAfterCrash)
            .putBoolean(KEY_BACKGROUND, settings.background)
            .putBoolean(KEY_SCREEN_OFF, settings.screenOff)
            .putBoolean(KEY_PROTECT_DELETE, settings.protectDeleting)
            .putInt(KEY_VEHICLE_INTERVAL, settings.vehicleIntervalMs)
            .putStringSet(KEY_VEHICLE_EXTRA, settings.extraVehicleGroups)
            .putInt(KEY_BRAKE_ENTER, settings.brakeEnter)
            .putInt(KEY_BRAKE_EXIT, settings.brakeExit)
            .putInt(KEY_ACCEL_ENTER, settings.accelEnter)
            .putInt(KEY_ACCEL_EXIT, settings.accelExit)
            .putInt(KEY_DEBOUNCE, settings.debounceMs)
            .putInt(KEY_MIN_EVENT, settings.minEventMs)
            .putInt(KEY_MERGE, settings.mergeMs)
            .putBoolean(KEY_EVENT_PROTECT, settings.eventProtect)
            .putString(KEY_PLAYBACK_LAYOUT, settings.playbackLayout)
            .putFloat(KEY_PLAYBACK_SPEED, settings.playbackSpeed)
            .putString(KEY_PLAYBACK_ON_END, settings.playbackOnEnd)
            .putBoolean(KEY_TRACK_EXPANDED, settings.trackExpanded)
            .putString(KEY_THEME, settings.themeMode)
            .apply()
    }

    private fun <T> fallback(key: String, value: T): T {
        Log.w(TAG, "settings key '$key' 非法，回落默认值并记录诊断事件")
        return value
    }

    companion object {
        private const val TAG = "AutoRecordSettings"
        private const val FILE = "record"

        const val KEY_STORAGE_UUID = "storage.volumeUuid"
        const val KEY_QUOTA = "storage.quotaBytes"
        const val KEY_ROTATE = "storage.rotate"
        const val KEY_MIN_FREE = "storage.minFreeBytes"
        const val KEY_SEGMENT = "record.segmentSeconds"
        const val KEY_CODEC = "encode.codec"
        const val KEY_IMPL = "encode.impl"
        const val KEY_HEIGHT = "encode.height"
        const val KEY_FPS = "encode.fps"
        const val KEY_BITRATE = "encode.bitrate"
        const val KEY_GOP = "encode.gopSeconds"
        const val KEY_AUDIO = "encode.audio"
        const val KEY_AUTO_BOOT = "record.autoOnBoot"
        const val KEY_AUTO_LAUNCH = "record.autoOnLaunch"
        const val KEY_RECOVER = "record.recoverAfterCrash"
        const val KEY_BACKGROUND = "record.background"
        const val KEY_SCREEN_OFF = "record.screenOff"
        const val KEY_PROTECT_DELETE = "record.protectDeleting"
        const val KEY_VEHICLE_INTERVAL = "vehicle.intervalMs"
        const val KEY_VEHICLE_EXTRA = "vehicle.extraKeys"
        const val KEY_BRAKE_ENTER = "event.brake.enter"
        const val KEY_BRAKE_EXIT = "event.brake.exit"
        const val KEY_ACCEL_ENTER = "event.accel.enter"
        const val KEY_ACCEL_EXIT = "event.accel.exit"
        const val KEY_DEBOUNCE = "event.debounceMs"
        const val KEY_MIN_EVENT = "event.minEventMs"
        const val KEY_MERGE = "event.mergeMs"
        const val KEY_EVENT_PROTECT = "event.protect"
        const val KEY_PLAYBACK_LAYOUT = "playback.layout"
        const val KEY_PLAYBACK_SPEED = "playback.speed"
        const val KEY_PLAYBACK_ON_END = "playback.onEnd"
        const val KEY_TRACK_EXPANDED = "ui.trackExpanded"
        const val KEY_THEME = "ui.theme"
    }
}
