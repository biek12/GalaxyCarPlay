package com.shilapi.xcertplay

import android.content.Context

/**
 * 产品级“向iPhone提供车速与挡位”开关。
 *
 * 默认开启：数据本来就由本车 `AutoService` 采集，本App不需要ADB、Root或厂商SDK。
 * 这项只决定是否把车速/挡位附进 iAP2 定位报文，不改变定位来源；且仍受“向iPhone报告位置”
 * 总开关约束（核心用 locationReportingEnabled 与它相与）。
 *
 * 改这项会改变 iAP2 identification 里的能力位，因此只在下次连接生效，当前连接不受影响。
 */
internal object GalaxyVehicleOutputSettings {
    private const val PREFS = "galaxy_vehicle_outputs"
    private const val KEY_VEHICLE_SPEED = "vehicle_speed_to_iphone"

    fun vehicleSpeedToIphone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VEHICLE_SPEED, true)

    /** 用 commit 写入：设置页要在同一帧内读回新值，测试也需要确定的落盘结果。 */
    fun setVehicleSpeedToIphone(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VEHICLE_SPEED, enabled).commit()) { "vehicle_speed_setting_not_saved" }
    }
}