package com.galaxy.autocluster.ui.navigation

import com.galaxy.autocluster.R

/**
 * Cluster 普通页面的路由枚举：`stored` 保持旧版 `page` 键的原存储值，
 * 保存状态恢复用 [from] 解析，未知值回落首页，不引入第三方导航依赖。
 *
 * 旧整屏覆盖投屏已归档，THEMES 路由随之移除；旧保存值 `themes` 按既有未知值规则
 * 回落 [HOME]。主导航只剩仪表、设置两个入口；子页归属见 [mainNav]，
 * 标题返回与硬件返回关系见 [titleBack] / [hardwareBack]，均与旧版一致。
 */
enum class ClusterPage(
    val stored: String,
    val titleRes: Int,
) {
    HOME("home", R.string.cluster_page_home),
    OEM("oem", R.string.cluster_page_oem),
    MUSIC("music", R.string.cluster_page_music),
    BACKGROUND("background", R.string.cluster_page_background),
    SETTINGS("settings", R.string.cluster_page_settings),
    RESTORE("restore", R.string.cluster_page_restore),
    RESULT("result", R.string.cluster_page_result),
    ;

    /** 左侧主导航当前应选中的入口：子页一律归属仪表。 */
    val mainNav: ClusterPage
        get() =
            when (this) {
                SETTINGS -> SETTINGS
                else -> HOME
            }

    /** 标题返回目标；null 表示本页没有标题返回（home/settings/result）。 */
    val titleBack: ClusterPage?
        get() =
            when (this) {
                OEM -> HOME
                MUSIC -> OEM
                BACKGROUND -> OEM
                RESTORE -> HOME
                else -> null
            }

    /** 硬件返回目标；null 表示退出 Activity（仅 home）。 */
    val hardwareBack: ClusterPage?
        get() =
            when (this) {
                HOME -> null
                MUSIC -> OEM
                BACKGROUND -> OEM
                else -> HOME
            }

    companion object {
        /** 按旧存储值解析；`state?.getString("page")` 恢复时未知值（含 `themes`）回落 [HOME]。 */
        fun from(stored: String?): ClusterPage = entries.firstOrNull { it.stored == stored } ?: HOME
    }
}
