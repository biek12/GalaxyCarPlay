package com.galaxy.autocluster.ui.pages

import android.os.Bundle
import android.widget.FrameLayout
import com.galaxy.auto.ui.GalaxyStyle
import com.galaxy.auto.ui.GalaxyViews
import com.galaxy.autocluster.databinding.PageClusterMusicBinding
import com.galaxy.autocluster.ui.ClusterPageContent
import com.galaxy.autocluster.ui.ClusterPageHost
import com.galaxy.autocluster.ui.InstrumentMusicPreview
import com.galaxy.autocluster.ui.navigation.ClusterPage

/**
 * 音乐卡片页 binder：只绑定 `page_cluster_music`。公共开关行、状态说明、
 * 真实绘制预览槽（350dp）、四行/长句只读行与返回按钮；仅本页创建/更新预览，
 * 离开页面随槽位替换自然 detach，不复用旧预览。
 *
 * 常规快照不得 detach 预览：[onSnapshot] 只喂数据，[render] 只更新状态与开关回填，
 * 开关回填不触发业务回调。
 */
internal class MusicCardPage(
    private val binding: PageClusterMusicBinding,
    host: ClusterPageHost,
    initialSnapshot: Bundle,
    private val status: () -> String,
    private val checked: () -> Boolean,
) : ClusterPageContent {
    /** 本页唯一的真实绘制预览；创建时喂当前快照，detach 后由下一页重建。 */
    private val preview: InstrumentMusicPreview

    init {
        ClusterPageBindings.bindHeader(binding.pageHeader, ClusterPage.MUSIC, host)
        GalaxyViews.attachSwitch(
            binding.musicSwitchRow,
            binding.musicSwitchRow.titleView.text
                .toString(),
            checked(),
        ) { enabled -> host.musicSwitch(enabled) }
        preview =
            InstrumentMusicPreview(binding.root.context).also { view ->
                view.snapshot(initialSnapshot)
                binding.musicPreviewSlot.addView(view, FrameLayout.LayoutParams(-1, -1))
            }
        GalaxyStyle.button(binding.musicBackButton, primary = false, withIcon = false)
        binding.musicBackButton.setOnClickListener { host.nav(ClusterPage.OEM) }
    }

    /** 快照或配置变化后刷新状态说明，并按当前方案回填开关。 */
    override fun render() {
        binding.musicStatus.text = status()
        binding.musicSwitchRow.setSwitchChecked(checked())
    }

    /** 常规快照只更新预览数据，不重建页面、不拆装预览。 */
    override fun onSnapshot(value: Bundle) {
        preview.snapshot(value)
    }
}
