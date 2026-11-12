package com.galaxy.autocluster.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.autocluster.BackgroundImagePicker
import com.galaxy.autocluster.ClusterRenderService
import com.galaxy.autocluster.runtime.ClusterDisplayStore

/**
 * 一次提交的回执：提交方案与期望的仪表回执版本（pendingRevision）成对返回，
 * 由 Presenter 呈现"正在提交"直到 revision 与 plan 都对上。
 */
data class ClusterSubmitOutcome(
    val value: ClusterDisplayConfiguration,
    val pendingRevision: Long,
)

/**
 * Cluster 用户动作的唯一执行者：持有既有 [ClusterDisplayStore] 与宿主 Activity，
 * 执行提交、恢复、外观、主题入口与选图动作；不缓存第二份业务配置，
 * 读写只经原 Store 及同一 `cluster-display` prefs。
 *
 * 协议 action / 请求码 / extra / prefs 键按本 owner 的命名常量集中：
 * 服务 action 由 [ClusterRenderService.onStartCommand] 消费，
 * 请求码与 extra 与 [BackgroundImagePicker] 的既有合同一致，
 * `enabled` / `appearance` 键与 [ClusterDisplayStore] / `ClusterAppearance` 同一 prefs 文件。
 *
 * 线程/所有者：主线程调用；配置写入保持先 commit 再启动服务的原顺序，不改为异步持久化。
 */
internal class ClusterDisplayActions(
    private val activity: Activity,
    private val store: ClusterDisplayStore,
) {
    /** 与 [ClusterDisplayStore] / [com.galaxy.autocluster.runtime.ClusterAppearance] 共用的同一 prefs 文件名。 */
    private val preferences = activity.getSharedPreferences(PREFS_NAME, 0)

    /** onCreate 已启用恢复只执行一次；不随 bind/render/onResume 重启输出。 */
    private var restoredOutput = false

    /**
     * 提交原厂增强方案：既有 Store 先同步保存完整配置与 enabled，
     * 再按 needsElements 发 apply 或 stop；pendingRevision 公式保持
     * `snapshot.clusterElementRevision + (acknowledgedPlan == value ? 0 : 1)`，
     * acknowledgedPlan 用原合同 `ClusterDisplayConfiguration.from` 解析。
     * 失败（保存校验或启动服务异常）原样抛出，由 Presenter 保留 Toast 并重读持久配置。
     */
    fun submitElements(
        value: ClusterDisplayConfiguration,
        snapshot: Bundle,
    ): ClusterSubmitOutcome {
        store.submitElements(value)
        val acknowledged = snapshot.getBundle("clusterElements")?.let { ClusterDisplayConfiguration.from(it) }
        val pendingRevision = snapshot.getLong("clusterElementRevision") + if (acknowledged == value) 0 else 1
        val intent = Intent(activity, ClusterRenderService::class.java)
        intent.action = if (value.needsElements) ACTION_APPLY else ACTION_STOP
        activity.startForegroundService(intent)
        return ClusterSubmitOutcome(value, pendingRevision)
    }

    /**
     * 恢复原厂确认：enabled=false 同步 commit 后向既有 RenderService 发 stop；
     * 主题与配置键保留，不删除任何旧配置。
     */
    fun restoreOriginal() {
        check(preferences.edit().putBoolean(PREF_ENABLED, false).commit()) { PREF_ENABLED }
        val intent = Intent(activity, ClusterRenderService::class.java)
        intent.action = ACTION_STOP
        activity.startForegroundService(intent)
    }

    /**
     * 写入日夜外观（auto/day/night）并返回当前 enabled；不在外观选择中开启显示。
     * `appearance` 键与 `ClusterAppearance.wrap` 读取的是同一持久键。
     */
    fun applyAppearance(value: String): Boolean {
        preferences.edit().putString(PREF_APPEARANCE, value).apply()
        return store.enabled
    }

    /** 只有真实已启用的 OEM 方案才重载；旧 OVERLAY/历史 enabled 不触发任何输出命令。 */
    fun reloadDisplay() {
        if (!store.oemEnabled) return
        val intent = Intent(activity, ClusterRenderService::class.java)
        intent.action = ACTION_RELOAD
        activity.startForegroundService(intent)
    }

    /** 外观选项持久值域：顺序 跟随系统/日间/夜间，与旧 settings 分支一致。 */
    val appearanceValues: List<String> = listOf("auto", "day", "night")

    /** 当前外观持久值；缺省 auto。 */
    fun currentAppearanceValue(): String = preferences.getString(PREF_APPEARANCE, "auto").orEmpty()

    /** 当前外观选项索引；未知持久值按跟随系统（0）呈现，与旧实现 coerceAtLeast(0) 一致。 */
    fun currentAppearanceIndex(): Int = appearanceValues.indexOf(currentAppearanceValue()).coerceAtLeast(0)

    /**
     * onCreate 时只对**真实已启用的原厂元素方案**恢复：无 action 启动让既有服务按
     * 持久配置自行恢复；每个实例只执行一次，不随切页或快照重启输出。
     * 旧 OVERLAY 与历史 enabled=true 只作历史，绝不在这里拉起旧整屏输出。
     */
    fun restoreEnabledOutputIfNeeded() {
        if (restoredOutput) return
        restoredOutput = true
        if (store.oemEnabled) activity.startForegroundService(Intent(activity, ClusterRenderService::class.java))
    }

    /** 打开车机自带图片选择器；请求码与结果 extra 沿 [BackgroundImagePicker] 原合同。 */
    fun pickImage() {
        activity.startActivityForResult(Intent(activity, BackgroundImagePicker::class.java), REQUEST_PICK_IMAGE)
    }

    companion object {
        const val PREFS_NAME = "cluster-display"
        const val PREF_ENABLED = "enabled"
        const val PREF_APPEARANCE = "appearance"

        /** 既有 RenderService 命令合同：应用元素方案。 */
        const val ACTION_APPLY = "apply"

        /** 既有 RenderService 命令合同：停止本 App 显示输出。 */
        const val ACTION_STOP = "stop"

        /** 既有 RenderService 命令合同：外观变化后重载主题输出。 */
        const val ACTION_RELOAD = "reload"

        /** 与 [BackgroundImagePicker] 约定的选图请求码；Presenter 判定回执使用同一常量。 */
        const val REQUEST_PICK_IMAGE = 41

        /** 与 [BackgroundImagePicker] 约定的结果 extra：本机已选文件路径。 */
        const val RESULT_EXTRA_IMAGE_PATH = "imagePath"
    }
}
