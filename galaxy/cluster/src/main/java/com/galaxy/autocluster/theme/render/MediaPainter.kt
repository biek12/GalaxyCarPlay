package com.galaxy.autocluster.theme.render

import android.graphics.*
import com.galaxy.autocluster.theme.*
import kotlin.math.*

internal data class ArtworkFrame(val current: Bitmap?, val previous: Bitmap?, val changedAt: Long,val blurred: Map<Int,Bitmap> = emptyMap())

internal class MediaPainter(private val tools: DrawTools) {
    private val rect = RectF()
    private val path = Path()
    private val trackStarted = mutableMapOf<String, Pair<String, Long>>()
    fun artwork(canvas: Canvas, item: SceneRenderNode, artwork: ArtworkFrame, motion: SceneMotion) {
        val p = item.properties; val w = item.node.rect.width; val h = item.node.rect.height
        rect.set(0f, 0f, w, h); path.reset()
        when (p.string("shape")) {
            "circle" -> path.addCircle(w / 2, h / 2, min(w, h) / 2, Path.Direction.CW)
            "rounded" -> path.addRoundRect(rect, p.number("cornerRadius"), p.number("cornerRadius"), Path.Direction.CW)
            else -> path.addRect(rect, Path.Direction.CW)
        }
        val save = canvas.save(); canvas.clipPath(path)
        canvas.drawRect(rect, tools.fill(p.color("placeholderColor")))
        val duration = p.number("fadeMs").toLong()
        val fraction = if (duration <= 0) 1f else ((motion.now - artwork.changedAt).toFloat() / duration).coerceIn(0f, 1f)
        if (artwork.previous != null && fraction < 1) drawImage(canvas, artwork.previous, w, h, "cover", if(artwork.current==null)((1-fraction)*255).toInt()else 255)
        artwork.current?.let { drawImage(canvas, it, w, h, "cover", (fraction * 255).toInt()) }
        if (fraction < 1 && (artwork.current != null||artwork.previous!=null)) motion.animate()
        canvas.restoreToCount(save)
    }

    fun track(canvas: Canvas, item: SceneRenderNode, media: SceneMedia, motion: SceneMotion) {
        val p = item.properties; val w = item.node.rect.width; val h = item.node.rect.height
        tools.prepare(p)
        val state = trackStarted[item.node.id]
        if (state?.first != media.track) trackStarted[item.node.id] = media.track to motion.now
        val start = trackStarted.getValue(item.node.id).second
        var y = p.number("titleSize")
        val rows = mutableListOf((media.title.ifEmpty { "等待音乐" }) to p.number("titleSize"), media.artist to p.number("artistSize"))
        if (p.bool("showAlbum")) rows.add(media.album to p.number("artistSize"))
        val saved = canvas.save(); canvas.clipRect(0f, 0f, w, h)
        for ((index, row) in rows.withIndex()) {
            val (value, size) = row; tools.text.textSize = size
            val length = tools.text.measureText(value)
            val color = p.color(if (index == 0) "foreground" else "muted")
            if (p.string("overflow") == "marquee" && length > w) {
                val distance = length - w; val speed = p.number("marqueeSpeed")
                val travel = distance / speed * 1000
                val period = travel * 2 + 2000
                val phase = ((motion.now - start) % period.toLong())
                val offset = when { phase < 1000 -> 0f; phase < 1000 + travel -> (phase - 1000) * speed / 1000; phase < 2000 + travel -> distance; else -> distance - (phase - 2000 - travel) * speed / 1000 }
                tools.label(canvas, value, -offset, y, size, color, align = Paint.Align.LEFT)
                motion.animate()
            } else tools.label(canvas, value, tools.alignedX(p, w), y, size, color, w, tools.alignment(p))
            y += size * p.number("lineHeight") + p.number("gap")
        }
        canvas.restoreToCount(saved)
    }

    fun progress(canvas: Canvas, item: SceneRenderNode, media: SceneMedia, motion: SceneMotion) {
        val p = item.properties; val w = item.node.rect.width; val h = item.node.rect.height
        val elapsed = media.positionAt(motion.now); val duration = media.duration
        val thickness = p.number("trackHeight"); val y = min(h / 2, thickness)
        val radius = if (p.bool("roundEnds")) thickness / 2 else 0f
        rect.set(0f, y, w, y + thickness); canvas.drawRoundRect(rect, radius, radius, tools.fill(p.color("trackColor")))
        if (duration > 0 && elapsed >= 0) {
            rect.right = w * (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            canvas.drawRoundRect(rect, radius, radius, tools.fill(p.color("accent")))
        }
        tools.prepare(p)
        if (p.bool("showTime")) {
            val size = p.number("fontSize"); val baseline = min(h, y + thickness + size + 12)
            tools.label(canvas, tools.time(elapsed), 0f, baseline, size, p.color("muted"), w / 2, Paint.Align.LEFT)
            tools.label(canvas, tools.time(duration), w, baseline, size, p.color("muted"), w / 2, Paint.Align.RIGHT)
        }
        if (media.playing && elapsed >= 0 && (duration <= 0 || elapsed < duration)) {
            if (p.bool("showTime")) motion.deadline(motion.now + 1000 - elapsed % 1000)
            if (duration > 0) { val pixelMillis = max(1, (duration / w).toLong()); motion.deadline(motion.now + max(16, pixelMillis - elapsed % pixelMillis)) }
        }
    }

    fun drawImage(canvas: Canvas, image: Bitmap, w: Float, h: Float, fit: String, alpha: Int = 255) {
        val scale = if (fit == "cover") max(w / image.width, h / image.height) else min(w / image.width, h / image.height)
        val width = image.width * scale; val height = image.height * scale
        rect.set((w-width)/2,(h-height)/2,(w+width)/2,(h+height)/2)
        val paint = tools.fill(Color.WHITE); paint.alpha = alpha
        canvas.drawBitmap(image, null, rect, paint); paint.alpha = 255
    }
    fun clear() { trackStarted.clear() }
    fun retain(ids: Set<String>){trackStarted.keys.retainAll(ids)}
}
