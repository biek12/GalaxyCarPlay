package com.galaxy.autocluster.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.galaxy.auto.contract.LyricScroll
import com.galaxy.autocluster.preview.SceneData
import java.util.concurrent.Executors
import com.galaxy.auto.ui.R as UiR

/**
 * 320×316 仪表音乐卡片的动态预览。
 *
 * 只消费已经转换好的 [SceneData] 与封面代次，不发送媒体指令，也不拥有车辆采集。
 * 封面解码只在单线程 [worker] 上打开流；结果经 [View.post] 回到本 View 的线程，并用 [generation] 丢弃过期位图。
 * 脱离窗口时先作废代次、停止线程，再回收位图。坐标、四行歌词、滚动条件和停止顺序保持现有绘制合同。
 */
internal class InstrumentMusicPreview(
    context: Context,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var snapshot = Bundle()
    private var data = SceneData()
    private var image: Bitmap? = null
    private var imageVersion = Long.MIN_VALUE

    /** 封面任务代次。更换封面或脱离窗口时递增，迟到解码不得写回当前预览。 */
    private var generation = 0L

    /** 封面解码的唯一后台线程，由本 View 创建，在 [onDetachedFromWindow] 停止。 */
    private val worker = Executors.newSingleThreadExecutor()
    private var lineKey = ""
    private var lineStart = 0L
    private var previousPosition = 0L

    /**
     * 用调用方快照替换预览数据。
     *
     * 先复制 Bundle 并在当前线程重建 [SceneData]，绘制过程读的是这份副本。
     * 仅当 `artworkVersion` 变化时回收旧封面，并在 [worker] 解码新的 `artworkUri`。
     * 代次不一致的结果在回投时回收，不替换当前画面。解码失败保持无封面。
     */
    fun snapshot(value: Bundle) {
        snapshot = Bundle(value)
        data = SceneData(snapshot)
        val version = value.getLong("artworkVersion")
        if (version != imageVersion) {
            imageVersion = version
            val ticket = ++generation
            image?.recycle()
            image = null
            val source = value.getString("artworkUri")
            if (source != null) {
                worker.execute {
                    val decoded =
                        runCatching {
                            context.contentResolver.openInputStream(Uri.parse(source))?.use {
                                BitmapFactory.decodeStream(it)
                            }
                        }.getOrNull()
                    post {
                        if (generation == ticket) {
                            image = decoded
                            invalidate()
                        } else {
                            decoded?.recycle()
                        }
                    }
                }
            }
        }
        invalidate()
    }

    /**
     * 在本 View 线程按 320×316 卡片坐标绘制当前快照。
     *
     * 进度时钟使用 [SystemClock.elapsedRealtime]，与媒体快照的接收时间同一单位。
     * [label] 只负责裁剪后的单行文字，[time] 只格式化已有毫秒值；滚动、四行歌词和进度条仍在本方法内按原顺序完成。
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 320f, height / 316f)
        if (scale <= 0) return
        val save = canvas.save()
        canvas.translate((width - 320 * scale) / 2, (height - 316 * scale) / 2)
        canvas.scale(scale, scale)
        val fg = context.getColor(UiR.color.galaxy_ui_text)
        val muted = context.getColor(UiR.color.galaxy_ui_muted)
        val control = context.getColor(UiR.color.galaxy_ui_control)
        val accent = context.getColor(UiR.color.galaxy_ui_accent)

        /** 在卡片宽度内裁剪并绘制一行文字，绘制后恢复画布，不保留裁剪。 */
        fun label(text: String, x: Float, y: Float, size: Float, color: Int, maxWidth: Float) {
            val state = canvas.save()
            canvas.clipRect(x, 0f, x + maxWidth, 316f)
            paint.color = color
            paint.textSize = size
            paint.typeface = Typeface.DEFAULT
            canvas.drawText(text, x, y, paint)
            canvas.restoreToCount(state)
        }

        paint.color = control
        canvas.drawRoundRect(0f, 0f, 80f, 80f, 4f, 4f, paint)
        image?.takeUnless { it.isRecycled }?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, 80f, 80f), paint)
        } ?: label("暂无封面", 8f, 45f, 14f, muted, 72f)
        val media = data.media
        val now = SystemClock.elapsedRealtime()
        val position = media.positionAt(now)
        label(media.title.ifBlank { "暂无播放内容" }, 100f, 28f, 26f, fg, 220f)
        label(media.artist, 100f, 65f, 18f, muted, 220f)
        val index = if (media.times == null) 0 else media.lyricIndex(now)
        val current =
            media.lyrics.getOrNull(index).orEmpty().ifBlank {
                if (media.title.isBlank()) "" else "暂无歌词"
            }
        val key = media.track + "\u0000" + index + "\u0000" + current
        if (key != lineKey || position < previousPosition - 200 || position - previousPosition > 1500) {
            lineKey = key
            lineStart = position.coerceAtLeast(0)
        }
        previousPosition = position
        label(media.lyrics.getOrNull(index - 1).orEmpty(), 0f, 133f, 18f, muted, 320f)
        paint.textSize = 32f
        val distance = (paint.measureText(current) - 320).coerceAtLeast(0f)
        val offset = LyricScroll.offset(distance, (position - lineStart).coerceAtLeast(0))
        val lineSave = canvas.save()
        canvas.clipRect(0f, 148f, 320f, 192f)
        label(current, -offset, 182f, 32f, fg, 320f + offset)
        canvas.restoreToCount(lineSave)
        label(media.lyrics.getOrNull(index + 1).orEmpty(), 0f, 215f, 20f, muted, 320f)
        label(media.lyrics.getOrNull(index + 2).orEmpty(), 0f, 250f, 18f, muted, 320f)
        paint.color = control
        canvas.drawRect(0f, 282f, 220f, 285f, paint)
        if (media.duration > 0 && position >= 0) {
            paint.color = accent
            canvas.drawRect(
                0f,
                282f,
                220f * (position.toFloat() / media.duration).coerceIn(0f, 1f),
                285f,
                paint,
            )
        }

        /** 把已有毫秒进度格式化为分秒；未知进度显示 `--:--`。 */
        fun time(ms: Long) = if (ms < 0) "--:--" else "%02d:%02d".format(ms / 60000, ms / 1000 % 60)

        label(time(position), 0f, 311f, 15f, muted, 100f)
        label(time(media.duration), 160f, 311f, 15f, muted, 60f)
        canvas.restoreToCount(save)
        if (media.playing) {
            if (distance > offset) postInvalidateOnAnimation() else postInvalidateDelayed(1000)
        }
    }

    /**
     * 脱离窗口时停止封面解码并释放位图。
     *
     * 顺序固定：先递增代次使在途结果失效，再停止 [worker]，然后回收当前位图，最后调用父类。
     * 不在此重开线程。再次进入页面由宿主新建本 View。
     */
    override fun onDetachedFromWindow() {
        ++generation
        worker.shutdownNow()
        image?.recycle()
        image = null
        super.onDetachedFromWindow()
    }
}
