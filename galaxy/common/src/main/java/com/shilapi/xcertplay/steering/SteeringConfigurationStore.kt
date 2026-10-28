package com.shilapi.xcertplay.steering

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 借鉴星枢ServiceConfigurationJson的版本化字段；仅保存用户确认的方控语义映射。
 *
 * D11兼容：开关沿用产品既有键 `xcertplay_airplay/galaxy_steering_enabled`（车型配置页与
 * GalaxyMediaKeys 读取的同一个键），新文件里的 `enabled` 只作为旧版本的镜像与新装默认值来源，
 * 读取顺序为 既有键 → 本文件 → 默认true，保证用户此前关闭/开启的选择不被新页面覆盖。
 * 映射本身只存在本文件 `configuration` 键，读取失败不覆盖原内容，避免丢用户已确认的映射。
 */
internal class SteeringConfigurationStore(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("galaxy_steering_learning", 0)
    private val productPreferences = app.getSharedPreferences(PRODUCT_PREFERENCES, 0)
    fun enabled(): Boolean = when {
        productPreferences.contains(LEGACY_ENABLED) -> productPreferences.getBoolean(LEGACY_ENABLED, true)
        preferences.contains("enabled") -> preferences.getBoolean("enabled", true)
        else -> true
    }
    fun setEnabled(enabled: Boolean) {
        // 双写：既有键是所有入口的权威值，本文件键保留给旧版本回读。
        check(preferences.edit().putBoolean("enabled", enabled).commit())
        check(productPreferences.edit().putBoolean(LEGACY_ENABLED, enabled).commit())
    }
    fun load(): SteeringConfiguration {
        val raw = preferences.getString("configuration", null) ?: return SteeringConfiguration()
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        val values = json.getJSONArray("bindings")
        require(values.length() <= 64)
        return SteeringConfiguration((0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            SteeringBinding(value.getInt("keyCode"), SteeringGesture.valueOf(value.getString("gesture")),
                SteeringAction.valueOf(value.getString("action")))
        }, json.getLong("clickWindowMs"), json.getLong("longPressMs")).validated()
    }
    fun save(configuration: SteeringConfiguration) {
        val value = configuration.validated()
        val json = JSONObject().put("version", 1).put("clickWindowMs", value.clickWindowMs)
            .put("longPressMs", value.longPressMs).put("bindings", JSONArray().apply {
                value.bindings.forEach { binding -> put(JSONObject().put("keyCode", binding.keyCode)
                    .put("gesture", binding.gesture.name).put("action", binding.action.name)) }
            }).toString()
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        check(preferences.edit().putString("configuration", json).commit())
    }
    companion object {
        private const val MAX_BYTES = 32_768
        /** 产品既有偏好文件与其方控开关键（GalaxyVehiclePreferences.steering 同源）。 */
        private const val PRODUCT_PREFERENCES = "xcertplay_airplay"
        private const val LEGACY_ENABLED = "galaxy_steering_enabled"
    }
}