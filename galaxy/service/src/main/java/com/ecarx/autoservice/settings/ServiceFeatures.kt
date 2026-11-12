package com.ecarx.autoservice.settings

import android.content.Context
import android.os.Bundle

/**
 * 用户选择与运行就绪状态分开。旧安装缺少键时保持既有能力全部开启。
 *
 * 2026-11-12：旧整屏覆盖投屏已归档。[ServiceFeature.PROJECTION] 及
 * `projectionEnabled` 键仍保留作历史兼容记录（旧客户端可继续写、[disableAll] 仍写 false），
 * 但读取与 [enabled] 对外一律恒为 false，[set] 写 true 也不得重新打开旧能力。
 * 车辆/ADB/Root 三个现行开关、automatic 与 [disableAll] 行为不变；
 * 不能用同时关闭 Root 的方式停用旧链。
 */
internal enum class ServiceFeature(
    val key: String,
) {
    PROJECTION("projectionEnabled"),
    VEHICLE("vehicleEnabled"),
    ADB("adbEnabled"),
    ROOT("rootEnabled"),
}

internal data class ServiceFeatures(
    val projection: Boolean = false,
    val vehicle: Boolean = true,
    val adb: Boolean = true,
    val root: Boolean = true,
) {
    /** PROJECTION 恒不可用；其余按用户选择返回。 */
    fun enabled(feature: ServiceFeature) =
        when (feature) {
            ServiceFeature.PROJECTION -> false
            ServiceFeature.VEHICLE -> vehicle
            ServiceFeature.ADB -> adb
            ServiceFeature.ROOT -> root
        }

    /** 快照按 [enabled] 发布，因此 `projectionEnabled` 兼容键恒为 false。 */
    fun bundle() =
        Bundle().apply {
            ServiceFeature.values().forEach { putBoolean(it.key, enabled(it)) }
        }

    companion object {
        const val ACTION_CHANGED = "features-changed"

        /**
         * 读取现行设置。`projectionEnabled` 旧键不参与能力判定：即使历史值为 true，
         * 归档后也不会返回可用的 projection；旧键本身仍留在 prefs 里作兼容记录。
         */
        fun read(context: Context): ServiceFeatures {
            val prefs = context.getSharedPreferences("platform", Context.MODE_PRIVATE)
            return ServiceFeatures(
                false,
                prefs.getBoolean(ServiceFeature.VEHICLE.key, true),
                prefs.getBoolean(ServiceFeature.ADB.key, true),
                prefs.getBoolean(ServiceFeature.ROOT.key, true),
            )
        }

        /** 先落盘再通知Service；在途worker也能立即看到关闭，不能等待下一次恢复周期。 */
        fun set(context: Context, feature: ServiceFeature, enabled: Boolean) {
            check(
                context
                    .getSharedPreferences("platform", 0)
                    .edit()
                    .putBoolean(feature.key, enabled)
                    .commit(),
            )
        }

        /** 全部关闭：旧 projectionEnabled 兼容键一并写 false，行为与归档前一致。 */
        fun disableAll(context: Context) {
            val edit = context.getSharedPreferences("platform", 0).edit().putBoolean("automatic", false)
            ServiceFeature.values().forEach { edit.putBoolean(it.key, false) }
            check(edit.commit())
        }
    }
}
