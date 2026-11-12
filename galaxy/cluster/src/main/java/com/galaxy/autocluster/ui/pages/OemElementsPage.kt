package com.galaxy.autocluster.ui.pages

import com.galaxy.autocluster.databinding.PageClusterOemBinding
import com.galaxy.autocluster.ui.ClusterPageContent
import com.galaxy.autocluster.ui.ClusterPageHost
import com.galaxy.autocluster.ui.navigation.ClusterPage

/**
 * 原厂增强页 binder：只绑定 `page_cluster_oem`，两个设置行与运行状态说明，
 * 不混入服务设置。音乐卡片行进入音乐页，背景行进入背景页。
 *
 * 动态值由 Presenter 经 [musicSummary]/[backgroundSummary]/[status] 提供。
 */
internal class OemElementsPage(
    private val binding: PageClusterOemBinding,
    host: ClusterPageHost,
    private val musicSummary: () -> String,
    private val backgroundSummary: () -> String,
    private val status: () -> String,
) : ClusterPageContent {
    init {
        ClusterPageBindings.bindHeader(binding.pageHeader, ClusterPage.OEM, host)
        ClusterPageBindings.bindAction(binding.oemMusicEntry) { host.nav(ClusterPage.MUSIC) }
        ClusterPageBindings.bindAction(binding.oemBackgroundEntry) { host.nav(ClusterPage.BACKGROUND) }
    }

    /** 快照或配置变化后刷新两个入口的当前值与状态说明。 */
    override fun render() {
        binding.oemMusicEntry.setValue(musicSummary())
        binding.oemBackgroundEntry.setValue(backgroundSummary())
        binding.oemStatus.text = status()
    }
}
