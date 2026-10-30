package com.ecarx.autoservice.settings

import android.content.Context
import android.os.Bundle

/** 用户选择与运行就绪状态分开。旧安装缺少键时保持既有能力全部开启。 */
internal enum class ServiceFeature(val key: String) {
    PROJECTION("projectionEnabled"),
    VEHICLE("vehicleEnabled"),
    ADB("adbEnabled"),
    ROOT("rootEnabled"),
}

internal data class ServiceFeatures(
    val projection: Boolean = true,
    val vehicle: Boolean = true,
    val adb: Boolean = true,
    val root: Boolean = true,
) {
    fun enabled(feature: ServiceFeature) = when (feature) {
        ServiceFeature.PROJECTION -> projection
        ServiceFeature.VEHICLE -> vehicle
        ServiceFeature.ADB -> adb
        ServiceFeature.ROOT -> root
    }

    fun bundle() = Bundle().apply {
        ServiceFeature.values().forEach { putBoolean(it.key, enabled(it)) }
    }

    companion object {
        const val ACTION_CHANGED = "features-changed"

        fun read(context: Context): ServiceFeatures {
            val prefs = context.getSharedPreferences("platform", Context.MODE_PRIVATE)
            return ServiceFeatures(
                prefs.getBoolean(ServiceFeature.PROJECTION.key, true),
                prefs.getBoolean(ServiceFeature.VEHICLE.key, true),
                prefs.getBoolean(ServiceFeature.ADB.key, true),
                prefs.getBoolean(ServiceFeature.ROOT.key, true),
            )
        }

        /** 先落盘再通知Service；在途worker也能立即看到关闭，不能等待下一次恢复周期。 */
        fun set(context: Context, feature: ServiceFeature, enabled: Boolean) {
            check(context.getSharedPreferences("platform", 0).edit().putBoolean(feature.key, enabled).commit())
        }

        fun disableAll(context: Context) {
            val edit = context.getSharedPreferences("platform", 0).edit().putBoolean("automatic", false)
            ServiceFeature.values().forEach { edit.putBoolean(it.key, false) }
            check(edit.commit())
        }
    }
}
