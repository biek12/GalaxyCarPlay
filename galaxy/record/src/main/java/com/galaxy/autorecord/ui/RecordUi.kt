package com.galaxy.autorecord.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.galaxy.autorecord.R
import kotlin.math.roundToInt

/**
 * AutoRecord 的 Flyme 色阶与原生控件口径（docs/AutoRecord-FlymeAuto样式基线-20261103.md）。
 *
 * 纪律：
 * - 按审定稿统一 `res/values{,-night}/record_theme.xml` 语义色；
 * - 只用原生 View + GradientDrawable/RippleDrawable/StateListDrawable，不引入 Compose/Material/动画库；
 * - 只绘制静态背景，不加 elevation、阴影、模糊；颜色在构造时取定，日夜切换由 Activity 重建完成。
 */
internal class RecordUi(val context: Context) {
    val background = context.getColor(R.color.record_background)
    val backgroundAlt = context.getColor(R.color.record_background_alt)
    val navigation = context.getColor(R.color.record_navigation)
    val navigationAlt = context.getColor(R.color.record_navigation_alt)
    val surface = context.getColor(R.color.record_surface)
    val control = context.getColor(R.color.record_control)
    val border = context.getColor(R.color.record_border)
    val dividerColor = context.getColor(R.color.record_divider)
    val textColor = context.getColor(R.color.record_text)
    val muted = context.getColor(R.color.record_muted)
    val disabled = context.getColor(R.color.record_disabled)
    val accent = context.getColor(R.color.record_accent)
    val accentSoft = context.getColor(R.color.record_accent_soft)
    val selectedColor = context.getColor(R.color.record_selected)
    val rippleColor = context.getColor(R.color.record_ripple)
    val connected = context.getColor(R.color.record_connected)
    val warning = context.getColor(R.color.record_warning)
    val danger = context.getColor(R.color.record_danger)
    val primaryText = context.getColor(R.color.record_primary_text)
    val track = context.getColor(R.color.record_track)
    val overlayScrim = context.getColor(R.color.record_overlay_scrim)
    val overlayText = context.getColor(R.color.record_overlay_text)

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + .5f).toInt()

    /** 文字角色：字号/行高/字重以已审定 AutoRecord 原型为准；行高随系统字体缩放。 */
    fun text(value: String, role: RecordRole): TextView = TextView(context).apply {
        text = value
        textSize = role.size.toFloat()
        typeface = Typeface.create(if (role.medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        setTextColor(if (role.muted) muted else textColor)
        if (role.lineHeight > 0) {
            // 行高按 sp 换算，随系统字体缩放；不用已废弃的 scaledDensity 直乘。
            setLineHeight(
                android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, role.lineHeight.toFloat(), resources.displayMetrics,
                ).roundToInt(),
            )
        }
    }

    fun setTextColor(view: TextView, color: Int) { view.setTextColor(color) }

    fun rounded(color: Int, radius: Int = 4): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    /** 卡片：4dp 圆角 + 1dp 描边（不加重阴影/模糊）。 */
    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(surface, 5)
        clipToOutline = true
    }

    fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 内容区底色：background → backgroundAlt 竖向渐变（样式基线 §6）。 */
    fun contentGradient(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(background, backgroundAlt),
    )

    /** 导航栏底色：navigation → navigationAlt 竖向渐变 + 右缘 1dp divider，不覆盖内容区。 */
    fun navRailBackground(): LayerDrawable {
        val fill = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(navigation, navigationAlt),
        )
        val edge = GradientDrawable().apply { setColor(dividerColor) }
        return LayerDrawable(arrayOf(fill, edge)).apply {
            setLayerWidth(1, dp(1).coerceAtLeast(1))
            setLayerGravity(1, Gravity.END or Gravity.FILL_VERTICAL)
        }
    }

    fun divider(): View = View(context).apply {
        setBackgroundColor(dividerColor)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 卡内相邻设置行分隔线：左右各留 20dp，只做行与行之间。 */
    fun rowSeparator(): View = View(context).apply {
        setBackgroundColor(dividerColor)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun sectionTitle(value: String): TextView = text(value, RecordRole.SECTION_TITLE)

    /** 说明/警告文字；错误必须就地显示，不用 Toast 代替。 */
    fun note(value: String, error: Boolean = false): TextView = text(value, RecordRole.FEEDBACK).apply {
        setTextColor(if (error) danger else muted)
        setPadding(dp(4), dp(6), dp(4), dp(6))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun primaryButton(label: String, onClick: () -> Unit): Button =
        button(label, primary = true, height = 48, size = 14f, onClick = onClick)

    fun secondaryButton(label: String, onClick: () -> Unit): Button =
        button(label, primary = false, height = 48, size = 14f, onClick = onClick)

    private fun button(label: String, primary: Boolean, height: Int, size: Float, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = size
            isAllCaps = false
            includeFontPadding = false
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            stateListAnimator = null
            backgroundTintList = null
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(height)
            minimumHeight = dp(height)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(disabled, if (primary) primaryText else textColor),
                ),
            )
            background = RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                rounded(if (primary) accent else control, 4),
                null,
            )
            setOnClickListener { onClick() }
        }

    /** 筛选 chip：未选中 control 底，选中 selected 底 + accent。 */
    fun chip(label: String, isSelected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            includeFontPadding = false
            gravity = Gravity.CENTER
            isSingleLine = true
            minWidth = 0
            minimumWidth = 0
            minimumHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(if (isSelected) accent else textColor)
            background = RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                rounded(if (isSelected) selectedColor else control, 4),
                null,
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun tab(label: String, selected: Boolean = false, onClick: () -> Unit): TextView =
        chip(label, selected, onClick).apply {
            setPadding(dp(12), 0, dp(12), 0)
            styleTab(this, selected)
        }

    fun styleTab(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) accent else muted)
        view.background = RippleDrawable(ColorStateList.valueOf(rippleColor),
            rounded(if (selected) selectedColor else android.graphics.Color.TRANSPARENT, 4), null)
    }

    fun transport(kind: String, label: String, onClick: () -> Unit): TextView =
        tab("", false, onClick).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            contentDescription = label
            setCompoundDrawables(glyphDrawable(kind, textColor).apply {
                setBounds(0, 0, dp(22), dp(22))
            }, null, null, null)
        }

    /** 标题栏徽标：accentSoft 底 + 语义色文字（日夜各自成立，不覆盖画面）。 */
    fun badge(label: String, color: Int): TextView = TextView(context).apply {
        text = label
        textSize = 11f
        includeFontPadding = false
        gravity = Gravity.CENTER
        isSingleLine = true
        setTextColor(color)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = rounded(accentSoft, 4)
    }

    /** 固定版本图标资源，导航仅切换 tint。 */
    fun glyphDrawable(kind: String, color: Int): Drawable = RecordIcons.drawable(context, kind, color)

    /** 现成资源图标；只按控件尺寸缩放，不自行描绘路径。 */
    fun glyph(kind: String, color: Int, inset: Int = 0): ImageView = ImageView(context).apply {
        setImageDrawable(RecordIcons.drawable(context, kind, color))
        setPadding(dp(inset), dp(inset), dp(inset), dp(inset))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 64dp 命中区、32dp 可见图标的图标按钮（返回等）。 */
    fun iconButton(kind: String, description: String, color: Int, onClick: () -> Unit): View =
        android.widget.FrameLayout(context).apply {
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), rounded(0), null)
            contentDescription = description
            isClickable = true
            isFocusable = true
            addView(
                glyph(kind, color, 0),
                android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER),
            )
            setOnClickListener { onClick() }
        }

    /** 开关视觉：58×30 轨、11×20 滑块、内缩 5dp；未选中轨取实体灰（样式基线 §5）。 */
    fun switchView(): Switch = Switch(context).apply {
        showText = false
        splitTrack = false
        switchMinWidth = dp(58)
        minimumWidth = dp(58)
        minimumHeight = dp(30)
        background = null
        thumbTintList = null
        trackTintList = null
        thumbDrawable = InsetDrawable(
            rounded(primaryText, 1).apply { setSize(dp(11), dp(20)) }, dp(5), dp(5), dp(5), dp(5),
        )
        trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), rounded(accent, 3).apply { setSize(dp(58), dp(30)) })
            addState(intArrayOf(), rounded(track, 3).apply { setSize(dp(58), dp(30)) })
        }
    }

    /** 页面滚动容器：内容 padding 16/8/16/24（窄屏也取 16，不因宽度另分档）。 */
    fun pageScroll(build: (LinearLayout) -> Unit): ScrollView {
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val body = column().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        build(body)
        scroll.addView(body, android.widget.FrameLayout.LayoutParams(-1, -2))
        return scroll
    }
}

/** 文字角色（字号/行高/字重）。 */
internal enum class RecordRole(val size: Int, val lineHeight: Int, val medium: Boolean = false, val muted: Boolean = false) {
    PAGE_TITLE(21, 29, medium = true),
    SECTION_TITLE(14, 22, medium = true, muted = true),
    LABEL(16, 24),
    VALUE(14, 22, muted = true),
    DESCRIPTION(12, 18, muted = true),
    FEEDBACK(12, 18, muted = true),
    NAV(14, 0, medium = true),
    BADGE(11, 0),
    OVERLAY(12, 0, medium = true),
}
