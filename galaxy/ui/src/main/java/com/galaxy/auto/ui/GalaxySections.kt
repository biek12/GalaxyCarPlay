package com.galaxy.auto.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.galaxy.auto.ui.databinding.GalaxyUiActionsBinding
import com.galaxy.auto.ui.databinding.GalaxyUiSectionBinding

/**
 * header / body / footer 的唯一组合入口：静态容器来自 `galaxy_ui_section.xml` 与 `galaxy_ui_actions.xml`。
 *
 * Card 只承载条目，标题/说明/页脚按空值显隐；动态 build 回调只负责往槽位里填内容。
 * 线程：主线程；返回的 Card 由调用方继续添加条目。
 */
object GalaxySections {
    /** 标题区 + 卡片 + 页脚；返回卡片供调用方追加设置行。 */
    fun add(
        parent: LinearLayout,
        title: String = "",
        description: String = "",
        footer: String = "",
        build: (GalaxySettingsCard) -> Unit,
    ): GalaxySettingsCard {
        val context = parent.context
        val binding = GalaxyUiSectionBinding.inflate(LayoutInflater.from(context), parent, false)
        if (title.isNotEmpty()) {
            binding.sectionTitle.text = title
            binding.sectionTitle.visibility = View.VISIBLE
            (binding.sectionTitle.layoutParams as LinearLayout.LayoutParams).bottomMargin =
                GalaxyViews.px(
                    context,
                    if (description.isEmpty()) {
                        R.dimen.galaxy_ui_section_title_gap
                    } else {
                        R.dimen.galaxy_ui_text_gap
                    },
                )
        }
        if (description.isNotEmpty()) {
            binding.sectionDescription.text = description
            binding.sectionDescription.visibility = View.VISIBLE
            (binding.sectionDescription.layoutParams as LinearLayout.LayoutParams).bottomMargin =
                GalaxyViews.px(context, R.dimen.galaxy_ui_section_title_gap)
        }
        build(binding.sectionCard)
        if (footer.isNotEmpty()) {
            binding.sectionFooter.text = footer
            binding.sectionFooter.visibility = View.VISIBLE
            (binding.sectionFooter.layoutParams as LinearLayout.LayoutParams).topMargin =
                GalaxyViews.px(context, R.dimen.galaxy_ui_section_title_gap)
        }
        parent.addView(
            binding.root,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = GalaxyViews.px(context, R.dimen.galaxy_ui_section_gap)
            },
        )
        return binding.sectionCard
    }

    /** 动作按钮独立成组，与卡片外缘对齐；不再用白卡包裹并叠加内边距。 */
    fun actions(parent: LinearLayout, footer: String = "", build: (LinearLayout) -> Unit) {
        val context = parent.context
        val binding = GalaxyUiActionsBinding.inflate(LayoutInflater.from(context), parent, false)
        build(binding.actionsContainer)
        if (footer.isNotEmpty()) {
            binding.actionsFooter.text = footer
            binding.actionsFooter.visibility = View.VISIBLE
            (binding.actionsFooter.layoutParams as LinearLayout.LayoutParams).topMargin =
                GalaxyViews.px(context, R.dimen.galaxy_ui_section_title_gap)
        }
        parent.addView(
            binding.root,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = GalaxyViews.px(context, R.dimen.galaxy_ui_section_gap)
            },
        )
    }
}
