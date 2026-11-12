package com.galaxy.autocluster.ui.pages

import com.galaxy.autocluster.ui.ClusterPageContent
import com.galaxy.autocluster.ui.ClusterPageHost
import com.galaxy.autocluster.databinding.PageClusterResultBinding
import com.galaxy.autocluster.databinding.PageClusterRestoreBinding
import com.galaxy.autocluster.databinding.PageClusterThemesBinding
import com.galaxy.autocluster.ui.navigation.ClusterPage
import com.galaxy.auto.ui.GalaxyStyle

/**
 * 主题入口页 binder：只绑定 `page_cluster_themes`，“我的主题”显式进入既有 ThemeActivity。
 * 页面无动态数据，静态骨架与事件在构造时绑定一次。
 */
internal class ThemeEntryPage(
    binding: PageClusterThemesBinding,
    host: ClusterPageHost,
) : ClusterPageContent {
    init {
        ClusterPageBindings.bindHeader(binding.pageHeader, ClusterPage.THEMES, host)
        ClusterPageBindings.bindAction(binding.themesEntryRow) { host.openThemes() }
    }

    /** 页面无动态数据；保留空实现以维持统一的页面内容合同。 */
    override fun render() {}
}

/**
 * 恢复确认页 binder：只绑定 `page_cluster_restore`。确认走 enabled=false 同步 commit
 * → stop → 结果页；取消不写配置不发命令。
 */
internal class RestoreConfirmationPage(
    binding: PageClusterRestoreBinding,
    host: ClusterPageHost,
) : ClusterPageContent {
    init {
        ClusterPageBindings.bindHeader(binding.pageHeader, ClusterPage.RESTORE, host)
        GalaxyStyle.button(binding.restoreConfirmButton, primary = true, withIcon = false)
        GalaxyStyle.button(binding.restoreCancelButton, primary = false, withIcon = false)
        binding.restoreConfirmButton.setOnClickListener { host.restoreConfirmed() }
        binding.restoreCancelButton.setOnClickListener { host.restoreCancelled() }
    }

    /** 页面无动态数据；确认/取消结果由 Presenter 切换到结果页或首页。 */
    override fun render() {}
}

/**
 * 结果页 binder：只绑定 `page_cluster_result`。未收到 restored 回执不得显示
 * “已确认恢复”；文案由 Presenter 经 [status] 提供。
 */
internal class DisplayResultPage(
    private val binding: PageClusterResultBinding,
    host: ClusterPageHost,
    private val status: () -> String,
) : ClusterPageContent {
    init {
        ClusterPageBindings.bindHeader(binding.pageHeader, ClusterPage.RESULT, host)
        GalaxyStyle.button(binding.resultBackButton, primary = true, withIcon = false)
        binding.resultBackButton.setOnClickListener { host.nav(ClusterPage.HOME) }
    }

    /** 快照投递后刷新恢复进度文案。 */
    override fun render() {
        binding.resultStatus.text = status()
    }
}
