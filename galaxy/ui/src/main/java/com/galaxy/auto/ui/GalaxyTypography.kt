package com.galaxy.auto.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 文字角色：字体/字号/颜色/行高的唯一来源是资源。
 *
 * 每个角色指向一个 `GalaxyUi.TextAppearance.*` 样式（字号、字重、颜色）和一个行高 dimens；
 * XML 用 `style=` 直接引用同一资源，代码创建时用 [GalaxyViews.text] 应用同一资源，
 * 只有确有动态参数的 [apply] 才按数值换算。线程：主线程。
 */
object GalaxyTypography {
    /**
     * 角色定义。
     *
     * [size]/[line]/[medium]/[secondary] 是保留的 legacy 元数据（既有调用方与 `L7Typography`
     * 读取），不再作为生产工厂的第二套有效样式；实际样式取 [textAppearanceRes] 与 [lineHeightRes]。
     */
    enum class Role(
        val size: Int,
        val line: Int,
        val medium: Boolean = false,
        val secondary: Boolean = true,
        val textAppearanceRes: Int = 0,
        val lineHeightRes: Int = 0,
    ) {
        PAGE_TITLE(
            24,
            32,
            true,
            false,
            R.style.GalaxyUi_TextAppearance_PageTitle,
            R.dimen.galaxy_ui_line_height_page_title,
        ),
        SECTION_TITLE(
            18,
            26,
            true,
            true,
            R.style.GalaxyUi_TextAppearance_SectionTitle,
            R.dimen.galaxy_ui_line_height_section_title,
        ),
        LABEL(
            20,
            28,
            false,
            false,
            R.style.GalaxyUi_TextAppearance_Label,
            R.dimen.galaxy_ui_line_height_label,
        ),
        VALUE(
            18,
            26,
            false,
            true,
            R.style.GalaxyUi_TextAppearance_Value,
            R.dimen.galaxy_ui_line_height_value,
        ),
        DESCRIPTION(
            16,
            24,
            false,
            true,
            R.style.GalaxyUi_TextAppearance_Description,
            R.dimen.galaxy_ui_line_height_description,
        ),
        FEEDBACK(
            16,
            24,
            false,
            true,
            R.style.GalaxyUi_TextAppearance_Feedback,
            R.dimen.galaxy_ui_line_height_feedback,
        ),
    }

    /** 动态参数入口：字号、行高与字重由调用方决定时使用；固定角色请走 [Role]。 */
    fun apply(view: TextView, size: Int, line: Int, medium: Boolean) {
        view.textSize = size.toFloat()
        view.typeface =
            Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        view.includeFontPadding = false
        view.setLineHeight((line * view.resources.displayMetrics.scaledDensity).roundToInt())
    }

    /** 创建指定角色的文字视图；实现委托 [GalaxyViews.text]，保证只有一份创建逻辑。 */
    fun text(context: Context, value: String, role: Role) = GalaxyViews.text(context, value, role)
}
