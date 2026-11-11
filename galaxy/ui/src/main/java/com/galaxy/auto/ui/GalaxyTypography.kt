package com.galaxy.auto.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 文字角色：字号/行高/字重/是否次要色的唯一来源。
 *
 * XML 中相同的角色由 `GalaxyUi.TextAppearance.*` 资源表达；本对象负责代码创建的文字，
 * 行高按 `scaledDensity` 换算，随系统字体放大。线程：主线程。
 */
object GalaxyTypography {
    /** 角色定义；`secondary` 决定默认文字色层次。 */
    enum class Role(
        val size: Int,
        val line: Int,
        val medium: Boolean = false,
        val secondary: Boolean = true,
    ) {
        PAGE_TITLE(24, 32, true, false),
        SECTION_TITLE(18, 26, true),
        LABEL(20, 28, false, false),
        VALUE(18, 26),
        DESCRIPTION(16, 24),
        FEEDBACK(16, 24),
    }

    /** 应用字号、字重与行高；行高使用 sp 语义，避免大字体下被压缩。 */
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
