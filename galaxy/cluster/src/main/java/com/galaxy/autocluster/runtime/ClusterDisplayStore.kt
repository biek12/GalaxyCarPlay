package com.galaxy.autocluster.runtime

import android.content.Context
import com.galaxy.auto.contract.ClusterDisplayConfiguration

/** 暂存方案不代表启用；已提交方案与运行意图一起落盘，旧配置键保持兼容。 */
internal class ClusterDisplayStore(context: Context) {
    private val preferences = context.getSharedPreferences("cluster-display", Context.MODE_PRIVATE)

    val enabled get() = preferences.getBoolean("enabled", false)

    /** 控件呈现实际请求，不能将安装时暂存的草稿显示成已开启。 */
    fun elements(): ClusterDisplayConfiguration = read().takeIf {
        enabled && it.mode == ClusterDisplayConfiguration.OEM_ELEMENTS
    } ?: ClusterDisplayConfiguration(mode = ClusterDisplayConfiguration.OEM_ELEMENTS, music = false)

    fun read() = ClusterDisplayConfiguration(
        mode = preferences.getString("mode", ClusterDisplayConfiguration.OVERLAY)!!,
        background = preferences.getString("background", ClusterDisplayConfiguration.ORIGINAL)!!,
        music = preferences.getBoolean("oemMusic", true),
        image = preferences.getString("backgroundImage", "").orEmpty(),
    )

    fun draft(): ClusterDisplayConfiguration = preferences.getString("draft",null)?.let { raw ->
        runCatching { val v=org.json.JSONObject(raw);ClusterDisplayConfiguration(v.getString("mode"),v.getString("background"),v.getBoolean("music"),v.optString("image")) }.getOrNull()
    } ?: read()

    fun saveDraft(value: ClusterDisplayConfiguration) {
        check(preferences.edit().putString("draft", encode(value)).commit())
    }

    /** 一次提交保存开关及完整方案，防止进程恢复读到“已选但未启用”的半份状态。 */
    fun submitElements(value: ClusterDisplayConfiguration) {
        require(value.mode == ClusterDisplayConfiguration.OEM_ELEMENTS)
        check(preferences.edit().putString("mode", value.mode)
            .putString("background", value.background).putBoolean("oemMusic", value.music)
            .putString("backgroundImage", value.image).putString("draft", encode(value))
            .putBoolean("enabled", value.needsElements).commit()) { "cluster_configuration_not_saved" }
    }

    private fun encode(value: ClusterDisplayConfiguration) = org.json.JSONObject()
        .put("mode", value.mode).put("background", value.background)
        .put("music", value.music).put("image", value.image).toString()

    fun save(value: ClusterDisplayConfiguration) {
        check(preferences.edit().putString("mode", value.mode)
            .putString("background", value.background).putBoolean("oemMusic", value.music)
            .putString("backgroundImage", value.image).commit()) { "cluster_configuration_not_saved" }
    }
}
