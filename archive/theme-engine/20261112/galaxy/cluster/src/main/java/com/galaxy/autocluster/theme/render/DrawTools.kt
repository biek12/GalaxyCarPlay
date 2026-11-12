package com.galaxy.autocluster.theme.render

import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import com.galaxy.autocluster.theme.VisualProperties
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

internal class DrawTools {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    private val formats = (0..3).map { DecimalFormat(if (it == 0) "0" else "0." + "0".repeat(it), DecimalFormatSymbols(Locale.ROOT)) }
    private data class Label(val value: String, val width: Int, val size: Float, val font: Typeface, val spacing: Float)
    private val labels = LruCache<Label, String>(160)
    fun format(value: Float?, decimals: Int = 0): String = if (value == null) "--" else formats[decimals.coerceIn(0, 3)].format(value.toDouble())
    fun prepare(p: VisualProperties) {
        text.typeface = p.typeface; text.letterSpacing = p.number("letterSpacing")
        text.alpha = 255; text.shader = null; text.style = Paint.Style.FILL
    }
    fun label(canvas: Canvas, value: String, x: Float, baseline: Float, size: Float, color: Int, width: Float = Float.MAX_VALUE,
              align: Paint.Align = Paint.Align.CENTER, alpha: Int = 255) {
        text.textSize = size; text.color = color; text.alpha = alpha; text.textAlign = align
        val key = Label(value, width.coerceAtMost(100000f).toInt(), size, text.typeface, text.letterSpacing)
        val output = if (width == Float.MAX_VALUE) value else labels.get(key) ?: TextUtils.ellipsize(value, text, width, TextUtils.TruncateAt.END).toString().also { labels.put(key, it) }
        canvas.drawText(output, x, baseline, text)
    }
    fun fill(color: Int): Paint { paint.reset(); paint.isAntiAlias = true; paint.isFilterBitmap = true; paint.style = Paint.Style.FILL; paint.color = color; return paint }
    fun stroke(color: Int, width: Float, round: Boolean = false): Paint = fill(color).apply { style = Paint.Style.STROKE; strokeWidth = width; strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT }
    fun alignedX(p: VisualProperties, width: Float) = when (p.string("align")) { "left" -> 0f; "right" -> width; else -> width / 2 }
    fun alignment(p: VisualProperties) = when (p.string("align")) { "left" -> Paint.Align.LEFT; "right" -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
    fun time(milliseconds: Long): String = if (milliseconds < 0) "--:--" else "${milliseconds / 60000}:${(milliseconds / 1000 % 60).toString().padStart(2, '0')}"
    fun clearCache() { labels.evictAll() }
}
