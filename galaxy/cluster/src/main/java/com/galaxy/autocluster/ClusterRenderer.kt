package com.galaxy.autocluster

import android.graphics.*
import android.os.Bundle
import android.text.TextUtils
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/** 自有独立主题：地图由另一Surface提供，本Canvas只绘透明仪表层，不内置模拟车辆读数。 */
internal class ClusterRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val white = Color.rgb(239, 245, 248)
    private val muted = Color.rgb(154, 170, 178)
    private val blue = Color.rgb(31, 158, 255)
    private fun draw(canvas: Canvas, snapshot: Bundle, now: Long, panel: String, artwork: Bitmap?) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val saved = canvas.save()
        canvas.scale(canvas.width / 1920f, canvas.height / 532f)
        val signals = snapshot.getBundle("vehicle") ?: Bundle()
        fun value(key: String): String? {
            val item = signals.getBundle(key) ?: return null
            if (item.getString("status") != "VALID" || now - item.getLong("receivedAt") !in 0..2000) return null
            return item.getString("value")
        }
        fun number(key: String) = value(key)?.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(Locale.ROOT, it) } ?: "--"
        // 局部渐变保留地图，在数字/图标背后形成清晰对比；不把整张地图涂黑。
        shade(RectF(0f, 0f, 1920f, 85f), 230)
        shade(RectF(65f, 95f, 510f, 470f), 200)
        shade(RectF(1260f, 95f, 1870f, 485f), 205)
        label(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), 66f, 46f, 26f)
        label(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()), 200f, 46f, 24f, muted)
        label("${number("outsideTemperature")} °C", 470f, 46f, 24f)
        label(value("gear") ?: "--", 1640f, 46f, 28f)
        label(snapshot.getString("model", "L6"), 1730f, 46f, 23f, muted)
        for ((key, x) in listOf("leftTurn" to 750f, "lowBeam" to 810f, "highBeam" to 870f, "brakeLight" to 930f, "rightTurn" to 1110f)) {
            val on = value(key)?.toDoubleOrNull() == 1.0
            label(when (key) { "leftTurn" -> "←"; "rightTurn" -> "→"; "brakeLight" -> "●"; else -> "◖" },
                x, 46f, 30f, if (on) Color.rgb(38, 218, 112) else Color.DKGRAY)
        }
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 16f; paint.color = Color.rgb(52, 64, 72)
        val arc = RectF(130f, 112f, 490f, 472f)
        canvas.drawArc(arc, 135f, 270f, false, paint)
        val speed = value("displaySpeed")?.toDoubleOrNull()
        if (speed != null) { paint.color = blue; canvas.drawArc(arc, 135f, (270 * speed / 260).toFloat().coerceIn(0f, 270f), false, paint) }
        for (i in 0..13) {
            val angle = (135.0 + i * 270.0 / 13) * PI / 180
            label((i * 20).toString(), 310f + cos(angle).toFloat() * 140,
                292f + sin(angle).toFloat() * 140 + 6, 20f, muted, center = true)
        }
        label(number("displaySpeed"), 310f, 326f, 90f, center = true)
        label("km/h", 310f, 365f, 25f, muted, center = true)
        label(number("rpm"), 600f, 278f, 34f, center = true)
        label("rpm", 600f, 307f, 20f, muted, center = true)
        label("油门 ${number("accelerator")}%", 600f, 349f, 21f, blue, center = true)
        label("刹车 ${number("brake")}%", 600f, 382f, 21f, Color.rgb(235, 117, 90), center = true)
        val nav = snapshot.getBundle("navigation") ?: Bundle()
        label(nav.getString("distance").orEmpty(), 795f, 159f, 34f)
        label(nav.getString("road").orEmpty(), 795f, 205f, 23f, muted, maxWidth = 410f)
        label(nav.getString("remaining").orEmpty(), 755f, 412f, 25f)
        label(nav.getString("arrival").orEmpty(), 950f, 412f, 25f)
        label("纯电  ${number("evRange")} km", 735f, 480f, 22f)
        label("燃油  ${number("fuelRange")} km", 1010f, 480f, 22f)
        bar(canvas, 735f, 498f, 220f, value("soc")?.toFloatOrNull()?.div(100))
        bar(canvas, 1010f, 498f, 220f, value("fuel")?.toFloatOrNull()?.div(100))
        when (panel) {
            "TIRES" -> {
                label("胎压 · kPa", 1340f, 150f, 30f)
                for ((i, key) in listOf("frontLeft", "frontRight", "rearLeft", "rearRight").withIndex())
                    label(number("tire.$key"), 1370f + (i % 2) * 230f, 245f + (i / 2) * 130f, 40f)
            }
            "PEDALS" -> {
                label("车辆实时数据", 1340f, 150f, 30f)
                label("油门  ${number("accelerator")}%", 1340f, 230f, 32f)
                label("刹车  ${number("brake")}%", 1340f, 305f, 32f)
                label("方向盘  ${number("steeringAngle")}°", 1340f, 380f, 28f)
            }
            else -> music(canvas, snapshot.getBundle("media") ?: Bundle(), now, artwork)
        }
        if (speed == null) label("车辆读数未就绪", 120f, 505f, 18f, muted)
        canvas.restoreToCount(saved)
    }
    private lateinit var canvas: Canvas
    private fun shade(rect: RectF, opacity: Int) {
        paint.style = Paint.Style.FILL; paint.shader = null; paint.color = Color.argb(opacity, 4, 11, 16)
        canvas.drawRoundRect(rect, 34f, 34f, paint)
    }
    private fun label(value: String, x: Float, y: Float, size: Float, color: Int = white, center: Boolean = false, maxWidth: Float = 600f) {
        text.textSize = size; text.color = color; text.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        canvas.drawText(TextUtils.ellipsize(value, text, maxWidth, TextUtils.TruncateAt.END).toString(), x, y, text)
    }
    private fun bar(canvas: Canvas, x: Float, y: Float, width: Float, fraction: Float?) {
        paint.style = Paint.Style.FILL; paint.color = Color.DKGRAY; canvas.drawRoundRect(x, y, x + width, y + 5, 3f, 3f, paint)
        if (fraction != null) { paint.color = blue; canvas.drawRoundRect(x, y, x + width * fraction.coerceIn(0f, 1f), y + 5, 3f, 3f, paint) }
    }
    private fun music(canvas: Canvas, media: Bundle, now: Long, artwork: Bitmap?) {
        if (artwork != null && !artwork.isRecycled) {
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.alpha = 45
            canvas.drawBitmap(artwork, null, RectF(1280f, 95f, 1860f, 460f), paint); paint.alpha = 255
        }
        label(media.getString("title", "等待音乐"), 1350f, 145f, 28f, maxWidth = 470f)
        label(media.getString("artist").orEmpty(), 1350f, 180f, 21f, muted, maxWidth = 470f)
        val base = media.getLong("elapsed", -1)
        val elapsed = if (base < 0) -1 else base + if (media.getBoolean("playing")) (now - media.getLong("receivedAt", now)).coerceAtLeast(0) else 0
        val duration = media.getLong("duration", -1)
        val lyrics = media.getStringArrayList("lyrics").orEmpty()
        val times = media.getLongArray("lyricTimes")
        val index = if (times != null && times.size == lyrics.size && elapsed >= 0)
            times.indexOfLast { it in 0..elapsed }.coerceAtLeast(0) else -1
        for (offset in -2..2) {
            val line = if (index >= 0) lyrics.getOrNull(index + offset).orEmpty() else lyrics.getOrNull(offset + 2).orEmpty()
            label(line, 1350f, 294f + offset * 39, if (index >= 0 && offset == 0) 30f else 22f,
                if (index >= 0 && offset == 0) white else muted, maxWidth = 470f)
        }
        bar(canvas, 1350f, 433f, 410f, if (duration > 0 && elapsed >= 0) elapsed.toFloat() / duration else null)
        fun time(ms: Long) = if (ms < 0) "--:--" else "%d:%02d".format(ms / 60000, ms / 1000 % 60)
        label(time(elapsed), 1350f, 467f, 18f, muted); label(time(duration), 1705f, 467f, 18f, muted)
    }
    fun render(target: Canvas, snapshot: Bundle, now: Long, panel: String, artwork: Bitmap?) {
        canvas = target
        draw(target, snapshot, now, panel, artwork)
    }
}
