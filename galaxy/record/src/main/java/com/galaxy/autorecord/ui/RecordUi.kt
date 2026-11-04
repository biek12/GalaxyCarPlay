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
 * - 只用 `res/values{,-night}/record_theme.xml` 已有的语义色，不新增第二套调色板；
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

    /** 文字角色：字号/行高/字重与项目 L7Typography 一致；行高随系统字体缩放。 */
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
        background = rounded(surface, 4).apply { setStroke(dp(1).coerceAtLeast(1), border) }
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
        button(label, primary = true, height = 72, size = 20f, onClick = onClick)

    fun secondaryButton(label: String, onClick: () -> Unit): Button =
        button(label, primary = false, height = 48, size = 16f, onClick = onClick)

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

    /** 筛选 chip：未选中 control 底，选中 accent 底 + primaryText。 */
    fun chip(label: String, isSelected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 18f
            includeFontPadding = false
            gravity = Gravity.CENTER
            isSingleLine = true
            minWidth = 0
            minimumWidth = 0
            minimumHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(if (isSelected) primaryText else textColor)
            background = RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                rounded(if (isSelected) accent else control, 4),
                null,
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    /** 标题栏徽标：accentSoft 底 + 语义色文字（日夜各自成立，不覆盖画面）。 */
    fun badge(label: String, color: Int): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        includeFontPadding = false
        gravity = Gravity.CENTER
        isSingleLine = true
        setTextColor(color)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = rounded(accentSoft, 4)
    }

    /** 自绘图标的 drawable 形式（导航项切换选中色时复用同一套坐标）。 */
    fun glyphDrawable(kind: String, color: Int): Drawable = RecordGlyph(kind, color)

    /** 自绘图标（24px 画布、1.7 描边）；控件尺寸由调用方 LayoutParams 决定，inset 收窄绘制面积。 */
    fun glyph(kind: String, color: Int, inset: Int = 0): ImageView = ImageView(context).apply {
        setImageDrawable(RecordGlyph(kind, color))
        setPadding(dp(inset), dp(inset), dp(inset), dp(inset))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 64dp 命中区、32dp 可见图标的图标按钮（返回等）。 */
    fun iconButton(kind: String, description: String, color: Int, onClick: () -> Unit): View =
        android.widget.FrameLayout(context).apply {
            minimumWidth = dp(64)
            minimumHeight = dp(64)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), rounded(0), null)
            contentDescription = description
            isClickable = true
            isFocusable = true
            addView(
                glyph(kind, color, 4),
                android.widget.FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER),
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
    PAGE_TITLE(24, 32, medium = true),
    SECTION_TITLE(18, 26, medium = true, muted = true),
    LABEL(20, 28),
    VALUE(18, 26, muted = true),
    DESCRIPTION(16, 24, muted = true),
    FEEDBACK(16, 24, muted = true),
    NAV(19, 0, medium = true),
    BADGE(16, 0),
    OVERLAY(18, 0, medium = true),
}

/**
 * 自绘图标。坐标固定在 24px 画布内，1.7px 描边、圆头圆角，按控件尺寸等比缩放；
 * 不复制 OEM 素材、截图图标或第三方图标库。
 */
private class RecordGlyph(private val kind: String, private var color: Int) : Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        pen.color = color
        fill.color = color
        when (kind) {
            // 实时画面：2×2 四宫格。
            "live" -> {
                canvas.drawRoundRect(RectF(2f, 2f, 11f, 11f), 2f, 2f, pen)
                canvas.drawRoundRect(RectF(13f, 2f, 22f, 11f), 2f, 2f, pen)
                canvas.drawRoundRect(RectF(2f, 13f, 11f, 22f), 2f, 2f, pen)
                canvas.drawRoundRect(RectF(13f, 13f, 22f, 22f), 2f, 2f, pen)
            }
            // 录像库：片库外框 + 播放标记。
            "library" -> {
                canvas.drawRoundRect(RectF(2f, 4f, 22f, 20f), 2f, 2f, pen)
                canvas.drawPath(Path().apply {
                    moveTo(10f, 9f); lineTo(16f, 12f); lineTo(10f, 15f); close()
                }, pen)
            }
            // 设置：三条滑杆。
            "settings" -> {
                canvas.drawLine(3f, 7f, 21f, 7f, pen)
                canvas.drawLine(3f, 12f, 21f, 12f, pen)
                canvas.drawLine(3f, 17f, 21f, 17f, pen)
                canvas.drawCircle(9f, 7f, 2f, fill)
                canvas.drawCircle(15f, 12f, 2f, fill)
                canvas.drawCircle(7f, 17f, 2f, fill)
            }
            "back" -> canvas.drawPath(Path().apply { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }, pen)
            "chevron" -> canvas.drawPath(Path().apply { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }, pen)
            "expand" -> canvas.drawPath(Path().apply { moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) }, pen)
            "collapse" -> canvas.drawPath(Path().apply { moveTo(5f, 15f); lineTo(12f, 8f); lineTo(19f, 15f) }, pen)
            // 存储：介质/硬盘。
            "storage" -> {
                canvas.drawRoundRect(RectF(2f, 5f, 22f, 19f), 2f, 2f, pen)
                canvas.drawLine(2f, 14f, 22f, 14f, pen)
                canvas.drawCircle(18f, 16.5f, 1f, fill)
                canvas.drawCircle(14.5f, 16.5f, 1f, fill)
            }
            // 编码：芯片。
            "encode" -> {
                canvas.drawRoundRect(RectF(6f, 6f, 18f, 18f), 2f, 2f, pen)
                canvas.drawRect(9.5f, 9.5f, 14.5f, 14.5f, pen)
                canvas.drawLine(12f, 2f, 12f, 6f, pen)
                canvas.drawLine(12f, 18f, 12f, 22f, pen)
                canvas.drawLine(2f, 12f, 6f, 12f, pen)
                canvas.drawLine(18f, 12f, 22f, 12f, pen)
            }
            // 录制：摄像机。
            "record" -> {
                canvas.drawRoundRect(RectF(2f, 7f, 16f, 18f), 2f, 2f, pen)
                canvas.drawPath(Path().apply {
                    moveTo(16f, 12f); lineTo(22f, 8.5f); lineTo(22f, 16.5f); close()
                }, pen)
                canvas.drawCircle(9f, 12.5f, 2.2f, pen)
            }
            // 车辆：车身。
            "vehicle" -> {
                canvas.drawPath(Path().apply {
                    moveTo(3f, 15f); lineTo(5.5f, 8f); lineTo(18.5f, 8f); lineTo(21f, 15f)
                }, pen)
                canvas.drawRoundRect(RectF(2f, 13f, 22f, 19f), 2f, 2f, pen)
                canvas.drawLine(5f, 19f, 5f, 22f, pen)
                canvas.drawLine(19f, 19f, 19f, 22f, pen)
            }
            // 回放：画面 + 播放键。
            "playback" -> {
                canvas.drawRoundRect(RectF(2f, 4f, 22f, 20f), 2f, 2f, pen)
                canvas.drawPath(Path().apply {
                    moveTo(10f, 9f); lineTo(16f, 12f); lineTo(10f, 15f); close()
                }, pen)
            }
            // 诊断：脉搏。
            "diagnose" -> canvas.drawPath(Path().apply {
                moveTo(2f, 12f); lineTo(7f, 12f); lineTo(10f, 5f); lineTo(14f, 19f); lineTo(17f, 12f); lineTo(22f, 12f)
            }, pen)
            // 关于：信息圈。
            "about" -> {
                canvas.drawCircle(12f, 12f, 9f, pen)
                canvas.drawCircle(12f, 8f, 1f, fill)
                canvas.drawLine(12f, 11f, 12f, 16.5f, pen)
            }
            // 重新探测：环形箭头。
            "refresh" -> {
                canvas.drawArc(RectF(4f, 4f, 20f, 20f), -50f, 280f, false, pen)
                canvas.drawPath(Path().apply {
                    moveTo(20.5f, 3.5f); lineTo(20.5f, 8.5f); lineTo(15.5f, 8.5f)
                }, pen)
            }
            else -> {
                canvas.drawCircle(12f, 12f, 9f, pen)
                canvas.drawCircle(12f, 12f, 1f, fill)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { pen.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { pen.colorFilter = filter; fill.colorFilter = filter }
    @Deprecated("Drawable") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
