package com.galaxy.auto.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Flyme 静态样式：颜色、圆角、按压与禁用状态都来自 `color/` 与 `drawable/` 资源。
 *
 * 主题更新只重新解析资源并重绘已绑定控件，不重建视频 Surface 或连接会话。
 * 例外：[surface] 与 [rounded] 接受运行时颜色/半径，供宿主注入色板与自绘装饰使用。
 *
 * 线程/所有者：主线程操作 View；绑定的 lambda 由 View 持有，随视图一起释放。
 */
object GalaxyStyle {
    /** 一个可重新执行的样式绑定。 */
    private class PaletteBinding(
        val apply: () -> Unit,
    )

    /** 绑定样式并在当前配置下立即执行一次。 */
    fun bind(view: View, apply: () -> Unit) {
        view.setTag(R.id.galaxy_palette_binding, PaletteBinding(apply))
        apply()
    }

    /** 是否已经绑定过样式；用于避免覆盖调用方自行配置的控件。 */
    fun isBound(view: View) = view.getTag(R.id.galaxy_palette_binding) is PaletteBinding

    /** 递归重执行样式绑定；日夜或强调色变化后调用。 */
    fun refresh(view: View) {
        (view.getTag(R.id.galaxy_palette_binding) as? PaletteBinding)?.apply?.invoke()
        if (view is ViewGroup) for (index in 0 until view.childCount) refresh(view.getChildAt(index))
    }

    /** 文字颜色绑定。 */
    fun text(view: TextView, color: Int = R.color.galaxy_ui_text) {
        bind(view) { view.setTextColor(view.context.getColor(color)) }
    }

    /** 运行时填充色容器（宿主注入色板）。静态卡片/分组请使用 drawable 资源。 */
    fun surface(view: View, color: Int = R.color.galaxy_ui_surface, radius: Int = 4) {
        bind(view) { view.background = rounded(view.context, view.context.getColor(color), radius) }
    }

    /** 行级按压/悬停/焦点反馈；整行底色而非无边界光圈。 */
    fun rowFeedback(view: View) =
        bind(view) {
            view.background = view.context.getDrawable(R.drawable.galaxy_ui_row_feedback)
        }

    /**
     * 主/次按钮样式。形状、文字色与禁用色全部取资源；`compact` 只切换字号与尺寸档。
     *
     * [radius] 与 [surface] 为兼容参数：形状统一由 drawable 提供（历史调用方只传 4dp）；
     * 非默认 [surface] 时对次按钮内容层做 tint，宿主色板仍可生效。
     */
    fun button(
        view: Button,
        primary: Boolean = false,
        radius: Int = 4,
        compact: Boolean = false,
        surface: Int = R.color.galaxy_ui_control,
        withIcon: Boolean = true,
        decorate: ((Button) -> Unit)? = null,
    ) {
        view.isAllCaps = false
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        view.stateListAnimator = null
        view.backgroundTintList = null
        view.gravity = Gravity.CENTER
        view.includeFontPadding = false
        view.textSize = if (compact) 13f else 20f
        view.minWidth = 0
        view.minimumWidth = 0
        view.minHeight =
            GalaxyViews.px(
                view.context,
                if (compact) R.dimen.galaxy_ui_button_min_height_compact else R.dimen.galaxy_ui_button_min_height,
            )
        view.minimumHeight = view.minHeight
        bind(view) {
            val context = view.context
            view.backgroundTintList = null
            view.setTextColor(
                context.getColorStateList(
                    if (primary) R.color.galaxy_ui_button_primary_text else R.color.galaxy_ui_button_text,
                ),
            )
            view.background =
                context.getDrawable(
                    if (primary) R.drawable.galaxy_ui_button_primary else R.drawable.galaxy_ui_button_secondary,
                )
            if (!primary) applySurfaceTint(view, surface)
            // 替换背景后再次设置内边距，防止系统 Button 的 inset 覆盖统一的图标/文字留白。
            val horizontal =
                GalaxyViews.px(
                    context,
                    if (compact) {
                        R.dimen.galaxy_ui_button_padding_horizontal_compact
                    } else {
                        R.dimen.galaxy_ui_button_padding_horizontal
                    },
                )
            val vertical =
                if (compact) {
                    0
                } else {
                    GalaxyViews.px(
                        context,
                        R.dimen.galaxy_ui_button_padding_vertical,
                    )
                }
            view.setPaddingRelative(horizontal, vertical, horizontal, vertical)
            // 按当前可用状态直接重绘背景，避免主题切换复用旧 drawable 的状态与 tint。
            view.backgroundTintList = null
            view.invalidate()
            if (withIcon) {
                decorate?.invoke(view)
            } else {
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    null,
                    null,
                    null,
                    null,
                )
            }
        }
    }

    /** 输入框：细圆角填充与明确焦点边框，避免 Material 默认下划线。 */
    fun input(view: EditText) {
        view.textSize = 20f
        view.minimumHeight = GalaxyViews.px(view.context, R.dimen.galaxy_ui_input_min_height)
        bind(view) {
            val context = view.context
            view.setTextColor(context.getColor(R.color.galaxy_ui_text))
            view.setHintTextColor(context.getColor(R.color.galaxy_ui_muted))
            view.backgroundTintList = null
            view.background = context.getDrawable(R.drawable.galaxy_ui_input_background)
            val padding = GalaxyViews.px(context, R.dimen.galaxy_ui_input_padding)
            view.setPaddingRelative(padding, padding, padding, padding)
        }
    }

    /** 运行时圆角填充；仅用于宿主注入颜色或自绘装饰，静态形状请用资源。 */
    fun rounded(context: Context, color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * context.resources.displayMetrics.density
        }

    /** 宿主色板与公共默认不同时，仅对次按钮内容层染色；主按钮保持强调色。 */
    private fun applySurfaceTint(view: Button, surface: Int) {
        if (surface == R.color.galaxy_ui_control) return
        val content = (view.background as? RippleDrawable)?.getDrawable(0) ?: return
        content.setTintList(ColorStateList.valueOf(view.context.getColor(surface)))
    }
}
