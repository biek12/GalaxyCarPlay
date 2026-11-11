package com.galaxy.auto.ui

import android.widget.LinearLayout

/** header / body / footer 的唯一组合入口；Card 只承载条目。 */
object GalaxySections {
    fun add(parent: LinearLayout, title: String = "", description: String = "", footer: String = "",
            build: (GalaxySettingsCard) -> Unit): GalaxySettingsCard {
        val context = parent.context
        val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (title.isNotEmpty()) section.addView(GalaxyViews.sectionTitle(context, title), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = GalaxyViews.dp(context, if (description.isEmpty()) 12 else 4)
        })
        if (description.isNotEmpty()) section.addView(GalaxyTypography.text(context, description, GalaxyTypography.Role.DESCRIPTION),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = GalaxyViews.dp(context, 12) })
        val card = GalaxySettingsCard(context)
        build(card)
        section.addView(card)
        if (footer.isNotEmpty()) section.addView(GalaxyTypography.text(context, footer, GalaxyTypography.Role.FEEDBACK),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = GalaxyViews.dp(context, 12) })
        parent.addView(section, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = GalaxyViews.dp(context, 24) })
        return card
    }

    /** 动作按钮独立成组，与卡片外缘对齐；不再用白卡包裹并叠加内边距。 */
    fun actions(parent: LinearLayout, footer: String = "", build: (LinearLayout) -> Unit) {
        parent.addView(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            build(this)
            if (footer.isNotEmpty()) addView(GalaxyTypography.text(context, footer, GalaxyTypography.Role.FEEDBACK),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = GalaxyViews.dp(context, 12) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = GalaxyViews.dp(parent.context, 24) })
    }
}
