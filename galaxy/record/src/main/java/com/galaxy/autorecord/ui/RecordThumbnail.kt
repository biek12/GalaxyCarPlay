package com.galaxy.autorecord.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.View
import android.view.ViewTreeObserver
import com.galaxy.autorecord.core.SegmentEntry
import com.galaxy.autorecord.core.SegmentLayout
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 四向真实缩略图。历史 thumb.jpg 是单路，不能冒充四向；不改写原录像目录。 */
internal class RecordThumbnail(ui: RecordUi, entry: SegmentEntry) : View(ui.context) {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val segment = entry
    // 扫描线程已取得这些值；主线程构建卡片不再逐个 stat 四路视频。
    private val key = "${entry.dir.absolutePath}:${entry.bytes}:${entry.durationMillis}:${entry.completeness}:${entry.missingDirections}"
    private val visibleRect = Rect()
    private var requested = false
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { loadIfVisible() }

    init {
        contentDescription = "${RecordTime.wallTime(entry.startedAtMillis)} 四向缩略图"
        bitmap = cache.get(key)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
        post { loadIfVisible() }
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { loadIfVisible() }
    }

    private fun loadIfVisible() {
        if (bitmap != null || requested || !isAttachedToWindow || !isShown || !getGlobalVisibleRect(visibleRect)) return
        cache.get(key)?.let { bitmap = it; invalidate(); return }
        requested = request(key, segment, WeakReference(this))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        bitmap?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint) }
    }

    companion object {
        private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
        private val workers = ThreadPoolExecutor(1, 2, 15, TimeUnit.SECONDS,
            LinkedBlockingQueue(24), { runnable -> Thread(runnable, "record-thumbnails").apply { isDaemon = true } })
        private val pending = mutableMapOf<String, MutableList<WeakReference<RecordThumbnail>>>()

        private fun request(key: String, entry: SegmentEntry, target: WeakReference<RecordThumbnail>): Boolean = synchronized(pending) {
            pending[key]?.let { it.add(target); return@synchronized true }
            pending[key] = mutableListOf(target)
            try {
                workers.execute {
                    val result = runCatching { mosaic(entry) }.getOrNull()
                    if (result != null) cache.put(key, result)
                    val targets = synchronized(pending) { pending.remove(key).orEmpty().toList() }
                    targets.forEach { reference -> reference.get()?.post {
                        reference.get()?.let { view ->
                            view.requested = result != null
                            if (result != null) { view.bitmap = result; view.invalidate() }
                        }
                    } }
                }
                true
            } catch (_: java.util.concurrent.RejectedExecutionException) { pending.remove(key); false }
        }

        private fun mosaic(entry: SegmentEntry): Bitmap {
            val width = 480; val height = 300
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            val canvas = Canvas(out)
            canvas.drawColor(Color.BLACK)
            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f }
            SegmentLayout.DIRECTIONS.forEachIndexed { index, direction ->
                val x = index % 2 * 240f; val y = index / 2 * 150f
                val file = SegmentLayout.videoFile(entry.dir, direction)
                var frame: Bitmap? = null
                if (direction !in entry.missingDirections && file.isFile && file.length() > 0) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 150)
                    } catch (_: Exception) {
                        // 此格保留明确占位，不拿 thumb.jpg 或其他方向顶替。
                    } finally { runCatching { retriever.release() } }
                }
                frame?.let {
                    val scale = minOf(240f / it.width, 150f / it.height)
                    val w = it.width * scale; val h = it.height * scale
                    canvas.drawBitmap(it, null, RectF(x + (240-w)/2, y + (150-h)/2,
                        x + (240+w)/2, y + (150+h)/2), imagePaint)
                    it.recycle()
                }
                canvas.drawText(directionLabel(direction) + if (frame == null) " · 无画面" else "", x + 7, y + 18, label)
            }
            return out
        }
    }
}
