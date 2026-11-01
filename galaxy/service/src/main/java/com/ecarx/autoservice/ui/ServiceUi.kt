package com.ecarx.autoservice.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.*
import android.view.Gravity
import android.widget.*
import com.ecarx.autoservice.R

/** Flyme原生设置页的浅渐变、细圆角和方形短滑块；日夜随系统重建，不增加模糊开销。 */
internal class ServiceUi(val context: Context) {
    val background = context.getColor(R.color.service_background)
    val surface = context.getColor(R.color.service_surface)
    val navigation = context.getColor(R.color.service_navigation)
    val border = context.getColor(R.color.service_border)
    val foreground = context.getColor(R.color.service_foreground)
    val secondary = context.getColor(R.color.service_secondary)
    val accent = context.getColor(R.color.service_accent)
    val accentSoft = context.getColor(R.color.service_accent_soft)
    val success = context.getColor(R.color.service_success)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun label(value: String, size: Float = 18f, muted: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; includeFontPadding = false
        setTextColor(if (muted) secondary else this@ServiceUi.foreground)
        if (size >= 22f) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun column(padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun rounded(color: Int, radius: Int = 6) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    fun card(padding: Int = 20) = column(padding).apply {
        background = rounded(surface).apply { setStroke(dp(1), border) }
    }
    fun gradient() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(navigation, background))
    fun divider() = android.view.View(context).apply { setBackgroundColor(border) }
    fun button(title: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = title; textSize = 17f; isAllCaps = false; includeFontPadding = false
        stateListAnimator = null; backgroundTintList = null
        minWidth = 0; minimumWidth = 0; minHeight = dp(52); minimumHeight = dp(52)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setTextColor(if (primary) Color.WHITE else this@ServiceUi.foreground)
        background = RippleDrawable(ColorStateList.valueOf(accentSoft), rounded(if (primary) accent else navigation), null)
        setOnClickListener { action() }
    }
    fun glyph(kind: String) = ImageView(context).apply {
        setImageDrawable(ServiceGlyph(kind, accent)); background = rounded(accentSoft, 8)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun control() = Switch(context).apply {
        showText = false; splitTrack = false; switchMinWidth = dp(58); minimumHeight = dp(60)
        background = null; thumbTintList = null; trackTintList = null
        thumbDrawable = InsetDrawable(rounded(Color.WHITE, 1).apply { setSize(dp(11), dp(20)) }, dp(5), dp(5), dp(5), dp(5))
        trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), rounded(accent, 3).apply { setSize(dp(58), dp(30)) })
            addState(intArrayOf(), rounded(context.getColor(R.color.service_track), 3).apply { setSize(dp(58), dp(30)) })
        }
    }
}

/** 本项目自绘图标，不复制OEM素材；坐标统一在24px画布。 */
private class ServiceGlyph(private val kind: String, private val color: Int) : Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        pen.color = color
        when (kind) {
            "projection" -> {
                canvas.drawRoundRect(2f, 4f, 22f, 18f, 2f, 2f, pen)
                canvas.drawLine(8f, 22f, 16f, 22f, pen); canvas.drawLine(12f, 18f, 12f, 22f, pen)
                canvas.drawPath(Path().apply { moveTo(7f, 14f); lineTo(12f, 8f); lineTo(17f, 14f) }, pen)
            }
            "adb" -> {
                canvas.drawRoundRect(2f, 4f, 22f, 20f, 2f, 2f, pen)
                canvas.drawPath(Path().apply { moveTo(6f, 9f); lineTo(10f, 12f); lineTo(6f, 15f) }, pen)
                canvas.drawLine(13f, 15f, 18f, 15f, pen)
            }
            "root" -> {
                canvas.drawPath(Path().apply {
                    moveTo(12f, 2f); lineTo(21f, 6f); lineTo(20f, 14f); quadTo(18f, 20f, 12f, 23f)
                    quadTo(6f, 20f, 4f, 14f); lineTo(3f, 6f); close()
                }, pen)
                canvas.drawPath(Path().apply { moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f) }, pen)
            }
            "vehicle" -> {
                canvas.drawPath(Path().apply { moveTo(3f, 15f); lineTo(5f, 7f); lineTo(19f, 7f); lineTo(21f, 15f); close() }, pen)
                canvas.drawRoundRect(2f, 13f, 22f, 20f, 2f, 2f, pen)
                canvas.drawLine(5f, 20f, 5f, 23f, pen); canvas.drawLine(19f, 20f, 19f, 23f, pen)
                canvas.drawLine(6f, 16f, 8f, 16f, pen); canvas.drawLine(16f, 16f, 18f, 16f, pen)
            }
            else -> {
                canvas.drawArc(3f, 3f, 21f, 21f, -50f, 280f, false, pen)
                canvas.drawPath(Path().apply { moveTo(21f, 3f); lineTo(21f, 8f); lineTo(16f, 8f) }, pen)
                canvas.drawLine(12f, 7f, 12f, 12f, pen); canvas.drawLine(12f, 12f, 16f, 14f, pen)
            }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { pen.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { pen.colorFilter = filter }
    @Deprecated("Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
