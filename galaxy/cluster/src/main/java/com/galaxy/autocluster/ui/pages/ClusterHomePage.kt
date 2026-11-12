package com.galaxy.autocluster.ui.pages

import android.view.View
import com.galaxy.auto.ui.GalaxyStyle
import com.galaxy.autocluster.databinding.PageClusterHomeBinding
import com.galaxy.autocluster.ui.ClusterPageContent
import com.galaxy.autocluster.ui.ClusterPageHost
import com.galaxy.autocluster.ui.navigation.ClusterPage

/**
 * 首页 binder：只绑定 `page_cluster_home`，展示当前显示状态、原厂增强与自定义仪表入口，
 * enabled 时显示恢复入口。状态更新只改文案与可见性，不触发任何命令。
 *
 * 动态值由 Presenter 经 [currentDisplay]/[restoreVisible] 提供；本页不读配置存储。
 */
internal class ClusterHomePage(
    private val binding: PageClusterHomeBinding,
    host: ClusterPageHost,
    private val currentDisplay: () -> String,
    private val restoreVisible: () -> Boolean,
) : ClusterPageContent {
    init {
        ClusterPageBindings.bindHeader(binding.pageHeader, ClusterPage.HOME, host)
        ClusterPageBindings.bindAction(binding.homeOemEntry) { host.oemEntry() }
        ClusterPageBindings.bindAction(binding.homeThemesEntry) { host.openThemes() }
        GalaxyStyle.button(binding.homeRestoreButton, primary = false, withIcon = false)
        binding.homeRestoreButton.setOnClickListener { host.nav(ClusterPage.RESTORE) }
    }

    /** 快照或配置变化后刷新当前显示文案与恢复入口可见性。 */
    override fun render() {
        binding.homeStateRow.setValue(currentDisplay())
        binding.homeRestoreButton.visibility =
            if (restoreVisible()) View.VISIBLE else View.GONE
    }
}
