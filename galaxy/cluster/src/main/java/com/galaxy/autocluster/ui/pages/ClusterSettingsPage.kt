package com.galaxy.autocluster.ui.pages

import com.galaxy.autocluster.ui.ClusterPageContent
import com.galaxy.autocluster.ui.ClusterPageHost
import com.galaxy.autocluster.databinding.PageClusterSettingsBinding
import com.galaxy.autocluster.ui.navigation.ClusterPage

/**
 * 显示设置页 binder：只绑定 `page_cluster_settings`，现有三种外观走公共
 * `GalaxySelection` 弹窗；本页只绑定和回传确认值，不自己写 prefs。
 */
internal class ClusterSettingsPage(
    private val binding: PageClusterSettingsBinding,
    host: ClusterPageHost,
    private val currentValue: () -> String,
) : ClusterPageContent {
    init {
        ClusterPageBindings.bindHeader(binding.pageHeader, ClusterPage.SETTINGS, host)
        ClusterPageBindings.bindAction(binding.settingsAppearanceRow) { host.appearanceClicked() }
    }

    /** 外观变化 recreate 前后按当前持久值刷新行值。 */
    override fun render() {
        binding.settingsAppearanceRow.setValue(currentValue())
    }
}
