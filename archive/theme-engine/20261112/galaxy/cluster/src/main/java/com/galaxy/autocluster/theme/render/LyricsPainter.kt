package com.galaxy.autocluster.theme.render

import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import com.galaxy.autocluster.theme.*
import kotlin.math.*

/** 时间戳只决定当前行，统一场景时钟负责视觉切换；seek/换歌直接定位，不补播旧行。 */
internal class LyricsPainter(private val tools: DrawTools) {
    private data class State(var track: String, var index: Int, var previous: Int, var changed: Long, var lastPosition: Long,var scrollStart: Long)
    private val states = mutableMapOf<String, State>()
    private data class Key(val text: String, val width: Int, val size: Float, val font: Int, val spacing: Float, val align: String)
    private val layouts = LruCache<Key, StaticLayout>(80)

    fun draw(canvas: Canvas, item: SceneRenderNode, media: SceneMedia, motion: SceneMotion) {
        val p = item.properties; val w = item.node.rect.width; val h = item.node.rect.height
        tools.prepare(p)
        if (media.lyrics.isEmpty()) {
            tools.label(canvas, p.string("emptyText"), tools.alignedX(p, w), h / 2 + p.number("fontSize") / 3,
                p.number("fontSize"), p.color("lineColor"), w, tools.alignment(p))
            return
        }
        val index = media.lyricIndex(motion.now)
        val position = media.positionAt(motion.now)
        val state = states.getOrPut(item.node.id) { State(media.track, index, index, motion.now, position,position) }
        val changedTrack = state.track != media.track
        val seek = position < state.lastPosition - 200 || position - state.lastPosition > 1500
        if (changedTrack || seek || index != state.index) {
            val animate = !changedTrack && !seek && index == state.index + 1 && state.index >= 0 && p.string("transition") != "none"
            state.scrollStart=position
            state.previous = if (animate) state.index else index
            state.index = index; state.track = media.track; state.changed = motion.now
        }
        state.lastPosition = position
        val duration = p.number("transitionMs").toLong()
        val progress = if (duration <= 0 || state.previous == state.index) 1f else ((motion.now - state.changed).toFloat() / duration).coerceIn(0f, 1f)
        if (progress < 1) motion.animate()
        val fraction = SceneMotion.ease(progress, p.string("easing"))
        val base = if (p.string("transition") == "scroll") state.previous + (state.index - state.previous) * fraction else state.index.toFloat()
        val centerIndex = base.coerceAtLeast(0f)
        val visible = p.string("lines").toInt()
        val naturalRow = max(p.number("fontSize"), p.number("activeSize")) * p.number("lineHeight") + p.number("lineGap")
        val rowHeight = min(naturalRow, h / visible)
        val half = visible / 2
        val before = if (p.string("activeRow") == "second" || p.string("activeRow") == "auto" && visible == 4) 1 else half
        val after = visible - before - 1
        val firstY = (h - (visible - 1) * rowHeight) / 2
        val anchorY = firstY + before * rowHeight
        val saved = canvas.save(); canvas.clipRect(0f, max(0f, firstY-rowHeight/2), w, min(h, firstY+(visible-.5f)*rowHeight))
        val start = floor(centerIndex).toInt() - before - 1
        for (line in start..start + visible + 2) {
            val text = media.lyrics.getOrNull(line) ?: continue
            val distance = abs(line - centerIndex)
            val emphasis = if (index < 0) 0f else (1 - distance).coerceIn(0f, 1f)
            val outer = ((distance - 1f) / (half - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            val contextSize = p.number("fontSize") * (1f - (1f - p.number("outerSizeScale",1f)) * outer)
            val size = contextSize + (p.number("activeSize") - contextSize) * emphasis
            val relative = line - centerIndex
            val y = anchorY + relative * rowHeight
            if (y < -rowHeight || y > h + rowHeight || relative < -before-.8f || relative > after+.8f) continue
            var opacity = if (line < centerIndex) p.number("pastOpacity") else p.number("futureOpacity")
            opacity += (1 - opacity) * emphasis
            opacity *= 1f - (1f - p.number("outerOpacity",1f)) * outer
            if (p.bool("edgeFade")) opacity *= ((h / 2 - abs(y - h / 2)) / rowHeight).coerceIn(0f, 1f)
            if (p.string("transition") == "fade" && progress < 1 && line == state.index) opacity *= fraction
            // 动画字号量化到半像素，限制布局缓存增长；布局使用自己的TextPaint。
            val quantized = (size * 2).roundToInt() / 2f
            tools.text.textSize=quantized
            val longWidth=tools.text.measureText(text)
            if(line==index&&longWidth>w){
                val age=(position-state.scrollStart).coerceAtLeast(0)
                val distance=longWidth-w
                val offset=com.galaxy.auto.contract.LyricScroll.offset(distance,age)
                tools.text.color=mix(p.color("lineColor"),p.color("activeColor"),emphasis)
                tools.text.alpha=(opacity*255).roundToInt().coerceIn(0,255)
                tools.text.textAlign=android.graphics.Paint.Align.LEFT
                val lineSave=canvas.save();canvas.clipRect(0f,y-rowHeight/2,w,y+rowHeight/2)
                canvas.drawText(text,-offset,y-(tools.text.ascent()+tools.text.descent())/2,tools.text)
                canvas.restoreToCount(lineSave)
                if(media.playing&&offset<distance){if(age<800)motion.deadline(motion.now+800-age)else motion.animate()}
                continue
            }
            val key = Key(text, w.toInt(), quantized, p.typeface.hashCode(), p.number("letterSpacing"), p.string("align"))
            val layout = layouts.get(key) ?: buildLayout(key, p).also { layouts.put(key, it) }
            layout.paint.color = mix(p.color("lineColor"), p.color("activeColor"), emphasis)
            layout.paint.alpha = (opacity * 255).roundToInt().coerceIn(0, 255)
            val lineSaved = canvas.save(); canvas.translate(0f, y - layout.height / 2)
            layout.draw(canvas); canvas.restoreToCount(lineSaved)
        }
        canvas.restoreToCount(saved)
        val times = media.times
        if (media.playing && times != null) times.getOrNull(index + 1)?.let { boundary ->
            if (boundary > position) motion.deadline(motion.now + boundary - position)
        }
    }

    private fun buildLayout(key: Key, p: VisualProperties): StaticLayout {
        // StaticLayout自行定位；不能继承上一组件label的CENTER，否则歌词会左移并被裁掉。
        val paint = TextPaint(tools.text).apply { textAlign = android.graphics.Paint.Align.LEFT; textSize = key.size; typeface = p.typeface; letterSpacing = key.spacing }
        val alignment = when (key.align) { "left" -> Layout.Alignment.ALIGN_NORMAL; "right" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER }
        return StaticLayout.Builder.obtain(key.text, 0, key.text.length, paint, key.width.coerceAtLeast(1))
            .setAlignment(alignment).setIncludePad(false).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
    }
    private fun mix(a: Int, b: Int, fraction: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * fraction).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * fraction).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * fraction).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * fraction).toInt())
    fun clear() { states.clear(); layouts.evictAll() }
    fun retain(ids: Set<String>){states.keys.retainAll(ids)}
}
