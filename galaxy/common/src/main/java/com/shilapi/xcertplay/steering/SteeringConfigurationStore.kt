package com.shilapi.xcertplay.steering

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 借鉴星枢ServiceConfigurationJson的版本化字段；仅保存用户确认的方控语义映射。 */
internal class SteeringConfigurationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("galaxy_steering_learning", 0)
    fun enabled(): Boolean = preferences.getBoolean("enabled", true)
    fun setEnabled(enabled: Boolean) { check(preferences.edit().putBoolean("enabled", enabled).commit()) }
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
    companion object { private const val MAX_BYTES = 32_768 }
}
