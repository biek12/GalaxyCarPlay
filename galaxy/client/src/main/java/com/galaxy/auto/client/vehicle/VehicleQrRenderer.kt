package com.galaxy.auto.client.vehicle

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 把服务端 scanUrl 编成 512×512 黑白二维码。
 *
 * 一个页面只有一个后台线程。后到的地址覆盖还没开始的任务，同一地址复用上一次位图。
 * 位图交给 ImageView 之前由本对象持有；替换时先改 ImageView，再回收上一张。
 * 关闭后迟到结果只回收，不能再挂回页面。
 */
class VehicleQrRenderer(
    private val main: Handler = Handler(Looper.getMainLooper()),
    workerLooper: Looper? = null,
    private val encode: (String) -> Bitmap = ::encodeUrl,
) {
    private val thread: HandlerThread? =
        if (workerLooper == null) {
            HandlerThread(THREAD_NAME).apply { start() }
        } else {
            null
        }
    private val worker = Handler(workerLooper ?: thread!!.looper)

    @Volatile private var closed = false
    private var epoch = 0L
    private var running = false
    private var pendingUrl: String? = null
    private var pendingImage: ImageView? = null
    private var pendingAccept: (() -> Boolean)? = null
    private var pendingFailure: (() -> Unit)? = null
    private var cacheUrl: String? = null
    private var cacheBitmap: Bitmap? = null
    private var displayed: Bitmap? = null

    /**
     * 请求把 [scanUrl] 显示到 [image]。
     *
     * [accept] 在结果回到主线程时再判断一次。页面已关闭、地址已变或码已过期时返回 false，
     * 这张位图就不会挂上。编码失败且仍然接受时调用 [onFailure]，不生成替代图。
     */
    fun submit(
        scanUrl: String,
        image: ImageView,
        accept: () -> Boolean,
        onFailure: () -> Unit,
    ) {
        if (closed) {
            return
        }
        val cached = cacheBitmap
        if (scanUrl == cacheUrl && cached != null && !cached.isRecycled) {
            epoch += 1
            pendingUrl = null
            if (accept()) {
                handOver(image, cached)
            }
            return
        }
        pendingUrl = scanUrl
        pendingImage = image
        pendingAccept = accept
        pendingFailure = onFailure
        if (running) {
            // 正在编码的旧地址作废，完成后只取最后一次待处理地址。
            epoch += 1
            return
        }
        startNext()
    }

    /**
     * 页面不再显示二维码。
     *
     * 使在途任务失效并清掉 ImageView。已缓存的同一地址位图仍可复用，正在显示的那张不在这里回收。
     */
    fun clear(image: ImageView) {
        if (closed) {
            return
        }
        epoch += 1
        pendingUrl = null
        image.setImageDrawable(null)
        val previous = displayed
        displayed = null
        if (previous != null && previous !== cacheBitmap && !previous.isRecycled) {
            previous.recycle()
        }
    }

    /**
     * 关闭本页的编码线程。
     *
     * 只移除自己的后台队列并退出自己的线程，不清空主线程上其他任务。已经交出去的位图由调用方先解除引用。
     */
    fun close() {
        if (closed) {
            return
        }
        closed = true
        epoch += 1
        pendingUrl = null
        worker.removeCallbacksAndMessages(null)
        recycleQuietly(displayed)
        displayed = null
        if (cacheBitmap !== displayed) {
            recycleQuietly(cacheBitmap)
        }
        cacheBitmap = null
        cacheUrl = null
        thread?.quitSafely()
    }

    /**
     * 取出当前待处理地址并开始一次编码。
     *
     * 同一时刻只有一个任务在跑。完成后若期间又来了新地址，再取最后那一个。
     */
    private fun startNext() {
        val scanUrl = pendingUrl ?: return
        val image = pendingImage ?: return
        val accept = pendingAccept ?: return
        val failure = pendingFailure ?: return
        pendingUrl = null
        val ticket = ++epoch
        running = true
        worker.post {
            val bitmap = runCatching { encode(scanUrl) }.getOrNull()
            if (closed) {
                recycleQuietly(bitmap)
                return@post
            }
            main.post {
                running = false
                if (closed || ticket != epoch) {
                    recycleUnlessDisplayed(bitmap)
                    if (!closed) {
                        startNext()
                    }
                    return@post
                }
                if (bitmap == null) {
                    if (accept()) {
                        failure()
                    }
                    startNext()
                    return@post
                }
                if (!accept()) {
                    recycleQuietly(bitmap)
                    startNext()
                    return@post
                }
                val previousCache = cacheBitmap
                cacheUrl = scanUrl
                cacheBitmap = bitmap
                if (
                    previousCache != null &&
                    previousCache !== bitmap &&
                    previousCache !== displayed &&
                    !previousCache.isRecycled
                ) {
                    previousCache.recycle()
                }
                handOver(image, bitmap)
                startNext()
            }
        }
    }

    /**
     * 把位图交给 ImageView。
     *
     * 先替换控件引用，再回收上一张已移交的位图，避免回收仍在显示的图像。
     */
    private fun handOver(
        image: ImageView,
        bitmap: Bitmap,
    ) {
        if (bitmap.isRecycled || closed) {
            return
        }
        if (displayed === bitmap) {
            if (image.drawable == null) {
                image.setImageBitmap(bitmap)
            }
            return
        }
        val previous = displayed
        image.setImageBitmap(bitmap)
        displayed = bitmap
        if (previous != null && previous !== bitmap && !previous.isRecycled) {
            previous.recycle()
        }
    }

    /** 回收一张不再显示的位图。已经回收或正被 ImageView 持有时不动。 */
    private fun recycleUnlessDisplayed(bitmap: Bitmap?) {
        if (bitmap == null || bitmap === displayed || bitmap.isRecycled) {
            return
        }
        bitmap.recycle()
    }

    /** 回收调用方已经不再引用的位图。 */
    private fun recycleQuietly(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    companion object {
        private const val THREAD_NAME = "vehicle-account-qr"
        private const val EDGE = 512

        /**
         * 用现有 ZXing 把地址编成黑白位图。
         *
         * 不改链接、不加身份参数。512 和黑白是编码结果，不是界面尺寸。
         */
        fun encodeUrl(scanUrl: String): Bitmap {
            val matrix = QRCodeWriter().encode(scanUrl, BarcodeFormat.QR_CODE, EDGE, EDGE)
            val bitmap = Bitmap.createBitmap(EDGE, EDGE, Bitmap.Config.ARGB_8888)
            val pixels =
                IntArray(EDGE * EDGE) { index ->
                    if (matrix[index % EDGE, index / EDGE]) Color.BLACK else Color.WHITE
                }
            bitmap.setPixels(pixels, 0, EDGE, 0, 0, EDGE, EDGE)
            return bitmap
        }
    }
}
