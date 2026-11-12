package com.galaxy.autocluster.ui.pages

import android.view.View
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.ui.GalaxyStyle
import com.galaxy.autocluster.R
import com.galaxy.autocluster.databinding.PageClusterBackgroundBinding
import com.galaxy.autocluster.ui.ClusterPageContent
import com.galaxy.autocluster.ui.ClusterPageHost
import com.galaxy.autocluster.ui.navigation.ClusterPage

/**
 * 背景页单次渲染的动态数据：来源行选中值、说明资源与按钮可见性由 Presenter 计算。
 *
 * [selectedSource] 是当前生效的背景来源（原合同常量）；[noteRes] 为当前来源的说明，
 * 原厂背景无说明传 null；[draftHintVisible]/[changeImageVisible]/[pickNewVisible]
 * 与旧 background 分支的条件一一对应。
 */
internal data class BackgroundPageState(
    val selectedSource: String,
    val noteRes: Int?,
    val draftHintVisible: Boolean,
    val changeImageVisible: Boolean,
    val pickNewVisible: Boolean,
)

/**
 * 背景页 binder：只绑定 `page_cluster_background`。固定 4 个来源行（原厂/本地图片/
 * CarPlay 地图/本机地图）、选择状态、说明及选图/换图按钮；事件交回 Presenter/Actions。
 *
 * 选择 image 且暂无草稿图片时由 Presenter 先打开既有选择器，不提前提交。
 */
internal class BackgroundPage(
    private val binding: PageClusterBackgroundBinding,
    host: ClusterPageHost,
    private val state: () -> BackgroundPageState,
    private val status: () -> String,
) : ClusterPageContent {
    init {
        ClusterPageBindings.bindHeader(binding.pageHeader, ClusterPage.BACKGROUND, host)
        ClusterPageBindings.bindAction(binding.backgroundSourceOriginal) {
            host.backgroundSelected(ClusterDisplayConfiguration.ORIGINAL)
        }
        ClusterPageBindings.bindAction(binding.backgroundSourceImage) {
            host.backgroundSelected(ClusterDisplayConfiguration.IMAGE_BACKGROUND)
        }
        ClusterPageBindings.bindAction(binding.backgroundSourceCarplay) {
            host.backgroundSelected(ClusterDisplayConfiguration.CARPLAY_MAP)
        }
        ClusterPageBindings.bindAction(binding.backgroundSourceLocal) {
            host.backgroundSelected(ClusterDisplayConfiguration.LOCAL_MAP)
        }
        GalaxyStyle.button(binding.backgroundChangeImage, primary = false, withIcon = false)
        GalaxyStyle.button(binding.backgroundPickNewImage, primary = false, withIcon = false)
        GalaxyStyle.button(binding.backgroundBackButton, primary = false, withIcon = false)
        binding.backgroundChangeImage.setOnClickListener { host.pickNewImage() }
        binding.backgroundPickNewImage.setOnClickListener { host.pickNewImage() }
        binding.backgroundBackButton.setOnClickListener { host.nav(ClusterPage.OEM) }
    }

    /** 快照或配置变化后刷新来源选中值、状态、说明与按钮可见性。 */
    override fun render() {
        val value = state()
        val selected = value.selectedSource
        val context = binding.root.context
        val selectedLabel = context.getString(R.string.cluster_background_selected_value)
        binding.backgroundStatus.text = status()
        binding.backgroundSourceOriginal.setValue(
            if (selected ==
                ClusterDisplayConfiguration.ORIGINAL
            ) {
                selectedLabel
            } else {
                ""
            },
        )
        binding.backgroundSourceImage.setValue(
            if (selected ==
                ClusterDisplayConfiguration.IMAGE_BACKGROUND
            ) {
                selectedLabel
            } else {
                ""
            },
        )
        binding.backgroundSourceCarplay.setValue(
            if (selected ==
                ClusterDisplayConfiguration.CARPLAY_MAP
            ) {
                selectedLabel
            } else {
                ""
            },
        )
        binding.backgroundSourceLocal.setValue(
            if (selected ==
                ClusterDisplayConfiguration.LOCAL_MAP
            ) {
                selectedLabel
            } else {
                ""
            },
        )
        val noteRes = value.noteRes
        if (noteRes == null) {
            binding.backgroundNote.visibility = View.GONE
        } else {
            binding.backgroundNote.setText(noteRes)
            binding.backgroundNote.visibility = View.VISIBLE
        }
        binding.backgroundDraftNote.setText(R.string.cluster_background_draft_hint)
        binding.backgroundDraftNote.visibility =
            if (value.draftHintVisible) View.VISIBLE else View.GONE
        binding.backgroundChangeImage.visibility =
            if (value.changeImageVisible) View.VISIBLE else View.GONE
        binding.backgroundPickNewImage.visibility =
            if (value.pickNewVisible) View.VISIBLE else View.GONE
    }
}
