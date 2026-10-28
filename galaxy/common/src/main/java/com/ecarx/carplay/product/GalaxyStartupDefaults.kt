package com.ecarx.carplay.product

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** 主进程创建时调用；保留上游偏好空间，不改连接方式、认证来源及用户已存值。 */
object GalaxyStartupDefaults {
    const val PREFERENCES = "xcertplay_airplay"
    const val VEHICLE_PROFILE_KEY = "galaxy_vehicle_profile"
    private const val WIRELESS_MODE_KEY = "wireless_hotspot_mode"

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        var changed = false
        if (!preferences.contains(VEHICLE_PROFILE_KEY)) {
            editor.putString(VEHICLE_PROFILE_KEY, GalaxyProduct.INITIAL_VEHICLE_PROFILE)
            changed = true
        }
        if (!preferences.contains(WIRELESS_MODE_KEY)) {
            editor.putString(WIRELESS_MODE_KEY, WirelessHotspotMode.MANUAL.name)
            changed = true
        }
        // 一次提交到内存并异步落盘；重复启动可重入，不启动连接或等待外部服务。
        if (changed) editor.apply()
        // 车型已由本项目首验决策确定，仅初始化缺失模型；保留用户后续明确选择。
        val audio = context.getSharedPreferences("l7_audio_templates", Context.MODE_PRIVATE)
        if (!audio.contains("model")) {
            audio.edit().putString("model", "l6").putString("mode_l6", "l6")
                .putString("detected_model_reviewed", "l6").apply()
        }
    }
}
