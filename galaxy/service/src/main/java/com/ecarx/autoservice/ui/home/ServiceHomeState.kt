package com.ecarx.autoservice.ui.home

import android.content.res.Resources
import android.os.Bundle
import android.text.format.DateFormat
import com.ecarx.autoservice.R
import com.ecarx.autoservice.remote.RemoteConnectionStatus
import com.ecarx.autoservice.settings.ServiceFeatures
import org.json.JSONObject

/**
 * 远程连接的只读显示字段。
 *
 * 刻意不包含 secret/grant/origin：页面只显示是否开启、是否已配对、窗口结束时间与验证码，
 * 任何凭据都不进入 UI 状态或日志。
 */
internal data class ServiceRemoteDisplay(
    val enabled: Boolean,
    val paired: Boolean,
    val untilSeconds: Long,
    val code: String,
    val codeUntil: Long,
    val state: String?,
)

/**
 * 首页的纯展示状态：由既有设置读数与服务快照一次性转换而来。
 *
 * 不读写配置、不持有 View、不启动服务，也不复制协议状态源；文案全部来自资源。
 * `from` 与 [remoteStatusText] 都在主线程调用，无副作用即可直接断言。
 */
internal data class ServiceHomeState(
    val headline: Int,
    val badge: Int,
    val badgeReady: Boolean,
    val summary: CharSequence,
    val projectionState: Int,
    val adbState: Int,
    val rootState: Int,
    val wifiHelpVisible: Boolean,
    val wifiMessage: Int,
    val automatic: Boolean,
    val automaticDescription: Int,
    val features: ServiceFeatures,
    val projectionDescription: Int,
    val vehicleDescription: CharSequence,
    val adbDescription: CharSequence,
    val rootDescription: Int,
    val remoteStatus: CharSequence,
) {
    companion object {
        /**
         * 把设置、快照与系统 Wi-Fi 观测转换为页面状态。
         *
         * `connected` 只表示观察者已连上服务，不代表投屏/Root 就绪；
         * `foundation` JSON 解析失败时保留空对象语义（就绪判定自然为 false）。
         */
        fun from(
            resources: Resources,
            automatic: Boolean,
            features: ServiceFeatures,
            snapshot: Bundle,
            connected: Boolean,
            wifiEnabled: Boolean?,
            remote: ServiceRemoteDisplay,
            nowMillis: Long,
        ): ServiceHomeState {
            val foundation =
                runCatching {
                    JSONObject(snapshot.getString("foundation") ?: "{}")
                }.getOrElse { JSONObject() }
            val adbReady =
                features.adb &&
                    connected &&
                    foundation.optString("state") in
                    setOf(
                        "verified",
                        "adb_connected",
                    )
            val rootReady = features.root && connected && snapshot.getBoolean("rootReady")
            val projectionReady =
                features.projection && connected && snapshot.getBoolean("qnxReady")
            val wifiHelpVisible =
                automatic &&
                    connected &&
                    ((features.adb && !adbReady) || (features.root && !rootReady))
            val wifiMessage =
                if (wifiEnabled == false) {
                    R.string.service_wifi_off
                } else {
                    R.string.service_wifi_pending
                }
            val headline =
                when {
                    !automatic -> R.string.service_headline_paused
                    !connected -> R.string.service_headline_waiting
                    else -> R.string.service_headline_running
                }
            val summary =
                when {
                    !automatic -> resources.getString(R.string.service_summary_paused)
                    !connected -> resources.getString(R.string.service_summary_waiting)
                    else ->
                        resources.getString(
                            R.string.service_summary_running,
                            snapshot.getString("model", "L6"),
                        )
                }
            val badge =
                when {
                    !automatic -> R.string.service_badge_paused
                    connected -> R.string.service_badge_connected
                    else -> R.string.service_badge_connecting
                }

            /** 单项指标：关闭优先于未就绪。 */
            fun metric(requested: Boolean, ready: Boolean, waiting: Int) =
                when {
                    !automatic || !requested -> R.string.service_state_disabled
                    ready -> R.string.service_state_connected
                    else -> waiting
                }

            val pending = R.string.service_feature_pending
            val projectionDescription =
                when {
                    !features.projection -> R.string.service_projection_off
                    !automatic -> pending
                    projectionReady -> R.string.service_projection_ready
                    !features.root -> R.string.service_projection_no_root
                    else -> R.string.service_projection_on
                }
            val vehicleDescription: CharSequence =
                when {
                    !features.vehicle -> resources.getString(R.string.service_vehicle_off)
                    !automatic -> resources.getString(pending)
                    connected && snapshot.getInt("vehiclePropertyCount") > 0 ->
                        resources.getString(
                            R.string.service_vehicle_progress,
                            snapshot.getInt("vehicleValidCount"),
                            snapshot.getInt("vehicleSampledCount"),
                            snapshot.getInt("vehiclePropertyCount"),
                        )

                    !features.root -> resources.getString(R.string.service_vehicle_no_root)
                    else -> resources.getString(R.string.service_vehicle_waiting)
                }
            val adbDescription: CharSequence =
                when {
                    !features.adb -> resources.getString(R.string.service_adb_off)
                    !automatic -> resources.getString(pending)
                    adbReady ->
                        resources.getString(
                            R.string.service_adb_ready,
                            foundation.optInt("adbUid", -1),
                            foundation.optInt("tlsPort"),
                        )

                    foundation.optString("state") == "failed" -> resources.getString(R.string.service_adb_failed)
                    else -> resources.getString(R.string.service_adb_pending)
                }
            val rootDescription =
                when {
                    !features.root -> R.string.service_root_off
                    !automatic -> pending
                    rootReady -> R.string.service_root_ready
                    !features.adb -> R.string.service_root_no_adb
                    else -> R.string.service_root_on
                }
            return ServiceHomeState(
                headline = headline,
                badge = badge,
                badgeReady = automatic && connected,
                summary = summary,
                projectionState =
                    metric(
                        features.projection,
                        projectionReady,
                        if (snapshot.getBoolean("projectionRequested")) {
                            R.string.service_state_connecting
                        } else {
                            R.string.service_metric_waiting_projection
                        },
                    ),
                adbState =
                    metric(
                        features.adb,
                        adbReady,
                        R.string.service_metric_waiting_connection,
                    ),
                rootState = metric(features.root, rootReady, R.string.service_metric_waiting_root),
                wifiHelpVisible = wifiHelpVisible,
                wifiMessage = wifiMessage,
                automatic = automatic,
                automaticDescription =
                    if (automatic) {
                        R.string.service_automatic_description_checked
                    } else {
                        R.string.service_automatic_description
                    },
                features = features,
                projectionDescription = projectionDescription,
                vehicleDescription = vehicleDescription,
                adbDescription = adbDescription,
                rootDescription = rootDescription,
                remoteStatus = remoteStatusText(resources, remote, nowMillis),
            )
        }
    }
}

/**
 * 远程连接状态文案：判定顺序与迁移前一致。
 *
 * 关闭 → 已配对 → TLS/重连（只显示固定状态）→ 验证码过期 → 验证码 → 默认状态。
 * [ServiceRemoteDisplay.state] 为空时取默认文案；不含任何异常消息或凭据。
 */
internal fun remoteStatusText(
    resources: Resources,
    remote: ServiceRemoteDisplay,
    nowMillis: Long,
): CharSequence {
    val connection = resources.getString(RemoteConnectionStatus.labelResource(remote.state))
    return when {
        !remote.enabled -> resources.getString(R.string.service_remote_off)
        remote.paired ->
            resources.getString(
                R.string.service_remote_paired,
                connection,
                DateFormat.format("MM-dd HH:mm", remote.untilSeconds * 1000),
            )

        remote.state in
            setOf(
                "tls_initialization_failed",
                "tls_failed",
                "reconnecting",
            )
        -> connection

        remote.code.isNotBlank() && remote.codeUntil < nowMillis / 1000 ->
            resources.getString(R.string.service_remote_code_expired)

        remote.code.isNotBlank() -> resources.getString(R.string.service_remote_code, remote.code)
        else -> connection
    }
}
