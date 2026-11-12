package com.galaxy.autocluster.runtime

import android.content.res.Resources
import android.os.Bundle
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.autocluster.R

/**
 * 状态判定的纯输入集合：由 Presenter 从当前快照与持久配置组装，
 * 本类不读 View、不持 Context 生命周期、不触碰 Client/IO/Intent，也不复制配置存储。
 *
 * `enabled`/`mode` 来自持久配置（`store.enabled` 与 `store.read().mode`），
 * `elements` 是 `store.elements()` 的当前应用方案，`snapshot` 是观察连接投递的最新回执。
 */
data class ClusterStatusInput(
    val enabled: Boolean,
    val mode: String,
    val elements: ClusterDisplayConfiguration,
    val snapshot: Bundle,
    val connected: Boolean,
    val pendingRevision: Long,
)

/**
 * Cluster 各页共同消费的状态映射：快照与持久配置 → 用户可见文案。
 *
 * [elementStatus] 的判定顺序严格沿旧版 `ClusterActivity.elementStatus` 迁移，
 * 不得重排：①未启用或非原厂增强按原回执显示恢复中/已确认/关闭；②本页说明；
 * ③未连接；④automatic=false；⑤rootEnabled=false；⑥配置 pending/failed；
 * ⑦revision 未到 pendingRevision；⑧回执 plan 不等于当前方案；⑨本页 mounted；
 * ⑩原 clusterElementStatus 各状态与默认值。
 * 快照 Bundle 键为 AutoService 观察契约的原协议键，保持字面量与旧实现一致。
 *
 * 线程/所有者：无状态 object，主线程调用；只读输入，不发布任何动作。
 */
object ClusterDisplayStatus {
    /** 通用状态（原 `elementStatus()` 不带 element 参数的调用）。 */
    fun generalStatus(
        resources: Resources,
        input: ClusterStatusInput,
    ): String = elementStatus(resources, input, "")

    /**
     * 带页面维度的状态；[element] 取 `"music"` / `"background"` 时按本页说明与
     * 本页 mounted 回执细化，其余值走通用判定，与旧实现一致。
     */
    fun elementStatus(
        resources: Resources,
        input: ClusterStatusInput,
        element: String,
    ): String =
        when {
            // 旧 OVERLAY 已归档：即使历史 enabled=true 也只显示只读归档态。
            input.enabled && input.mode != ClusterDisplayConfiguration.OEM_ELEMENTS ->
                resources.getString(R.string.cluster_home_projection_archived)
            !input.enabled || input.mode != ClusterDisplayConfiguration.OEM_ELEMENTS ->
                when (input.snapshot.getString("clusterElementStatus")) {
                    "restoring", "active", "preparing", "waiting-content" ->
                        resources.getString(R.string.cluster_status_restoring)
                    "restored" -> resources.getString(R.string.cluster_status_restored)
                    else -> resources.getString(R.string.cluster_status_disabled)
                }
            element == "music" && !input.elements.music ->
                resources.getString(R.string.cluster_status_music_replacement_off)
            element == "background" && input.elements.background == ClusterDisplayConfiguration.ORIGINAL ->
                resources.getString(R.string.cluster_status_original_background)
            !input.connected -> resources.getString(R.string.cluster_status_waiting_connection)
            !input.snapshot.getBoolean("automatic", true) ->
                resources.getString(R.string.cluster_status_automatic_paused)
            !input.snapshot.getBundle("features").let { it?.getBoolean("rootEnabled", true) ?: true } ->
                resources.getString(R.string.cluster_status_root_disabled)
            input.snapshot.getString("clusterConfigurationStatus") == "pending" ->
                resources.getString(R.string.cluster_status_image_preparing)
            input.snapshot.getString("clusterConfigurationStatus") == "failed" ->
                resources.getString(R.string.cluster_status_image_failed)
            input.snapshot.getLong("clusterElementRevision") < input.pendingRevision ->
                resources.getString(R.string.cluster_status_submitting)
            input.snapshot
                .getBundle(
                    "clusterElements",
                )?.let { ClusterDisplayConfiguration.from(it) } != input.elements ->
                resources.getString(R.string.cluster_status_submitting)
            element == "music" &&
                input.snapshot
                    .getBundle(
                        "clusterElementRuntime",
                    )?.getBoolean("musicMounted") == true ->
                resources.getString(R.string.cluster_status_music_replaced)
            element == "background" &&
                input.snapshot.getBundle("clusterElementRuntime")?.getBoolean("backgroundMounted") == true ->
                resources.getString(R.string.cluster_status_background_replaced)
            else ->
                when (input.snapshot.getString("clusterElementStatus")) {
                    "active" -> resources.getString(R.string.cluster_status_running)
                    "failed" -> resources.getString(R.string.cluster_status_apply_failed)
                    "waiting-content" -> resources.getString(R.string.cluster_status_waiting_content)
                    "waiting-feedback" -> resources.getString(R.string.cluster_status_waiting_feedback)
                    "restoring" -> resources.getString(R.string.cluster_status_restoring)
                    "restored" -> resources.getString(R.string.cluster_status_restored)
                    "preparing" -> resources.getString(R.string.cluster_status_applying)
                    "waiting-root" -> resources.getString(R.string.cluster_status_waiting_root)
                    else -> resources.getString(R.string.cluster_status_saved)
                }
        }

    /** 首页"当前显示"四段判定，顺序与旧 home 分支一致。 */
    fun homeOverview(
        resources: Resources,
        input: ClusterStatusInput,
    ): String =
        when {
            !input.enabled ->
                if (input.snapshot.getString("clusterElementStatus") in
                    setOf("active", "preparing", "waiting-content", "restoring")
                ) {
                    resources.getString(R.string.cluster_status_restoring)
                } else {
                    resources.getString(R.string.cluster_home_factory_display)
                }
            // 旧整屏覆盖投屏已归档：旧 OVERLAY/未知模式只显示只读归档态，
            // 不再消费 qnxReady/MAP 在线状态展示“主题通道已连接/等待”。
            input.mode != ClusterDisplayConfiguration.OEM_ELEMENTS ->
                resources.getString(R.string.cluster_home_projection_archived)
            !input.connected -> resources.getString(R.string.cluster_status_waiting_connection)
            else -> elementStatus(resources, input, "")
        }

    /** 结果页文案：已启用走通用状态；只有收到 restored 回执才确认恢复。 */
    fun restoreResult(
        resources: Resources,
        input: ClusterStatusInput,
    ): String =
        when {
            input.enabled -> elementStatus(resources, input, "")
            input.snapshot.getString("clusterElementStatus") == "restored" ->
                resources.getString(R.string.cluster_status_restored)
            else -> resources.getString(R.string.cluster_result_withdrawn)
        }

    /** 背景来源的显示名：原 `backgroundName` 的固定映射，文案资源化。 */
    fun backgroundName(
        resources: Resources,
        value: String,
    ): String =
        when (value) {
            ClusterDisplayConfiguration.IMAGE_BACKGROUND ->
                resources.getString(
                    R.string.cluster_background_source_image,
                )
            ClusterDisplayConfiguration.CARPLAY_MAP -> resources.getString(R.string.cluster_background_source_carplay)
            ClusterDisplayConfiguration.LOCAL_MAP -> resources.getString(R.string.cluster_background_source_local)
            else -> resources.getString(R.string.cluster_background_source_original)
        }
}
