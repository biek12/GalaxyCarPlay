package com.ecarx.autoservice.projection

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.galaxy.auto.contract.AutoContract
import java.io.Closeable

/** Android私有VirtualDisplay只负责合成，不把Android屏号映射成QNX屏号。
 * 主线程拥有View/Surface；采集线程持续排空ImageReader，管道线程独占子进程。
 * 握手或写pipe阻塞时也不会持有Image阻塞RenderThread；3份RGBA邮箱只保留最新帧。
 */
internal class GalaxyHabComposition(
    private val context: Context,
    private val surfaceChanged: (String, Surface?) -> Unit,
    private val report: (String) -> Unit,
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("GalaxyPlay-hab-frames").apply { start() }
    private val worker = Handler(thread.looper)
    private val captureThread = HandlerThread("GalaxyPlay-hab-capture").apply { start() }
    private val capture = Handler(captureThread.looper)
    private val frames = GalaxyHabFrames()
    private lateinit var reader: ImageReader
    private var lastCaptureAt = 0L
    private var lastWrittenAt = 0L
    @Volatile private var capturedCount = 0L
    @Volatile private var submittedCount = 0L
    private var display: VirtualDisplay? = null
    private var presentation: CompositionWindow? = null
    @Volatile private var epoch = 0
    @Volatile private var closed = false
    @Volatile private var bridge: GalaxyHabProcess? = null
    @Volatile private var active = false
    private var reconnect = GalaxyHabReconnect()

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        try {
            reader = ImageReader.newInstance(GalaxyHabLayout.WIDTH, GalaxyHabLayout.HEIGHT, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({ source -> captureFrame(source) }, capture)
            // 首次创建自己的key，供授权测试工具在连接手机前配置自有QNX receiver。
            GalaxyHabKey.load(context).fill(0)
            val manager = context.getSystemService(DisplayManager::class.java)
            display = checkNotNull(manager.createVirtualDisplay("GalaxyPlay-HAB-composition",
                GalaxyHabLayout.WIDTH, GalaxyHabLayout.HEIGHT, 160, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY))
            presentation = CompositionWindow(context, checkNotNull(display).display).also { it.show() }
            report("HAB: composition ready map=1920x536 output=1920x532 external-theme=true")
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun setSessionActive(next: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || active == next) return
        active = next
        if (!next) setMapActive(false)
        val generation = ++epoch
        bridge?.close()
        worker.removeCallbacksAndMessages(null)
        worker.post {
            bridge?.finish(); bridge = null
            if (!next || closed || epoch != generation) return@post
            reconnect = GalaxyHabReconnect()
            connect(generation)
        }
    }

    private fun connect(generation: Int) {
        if (closed || epoch != generation) return
        try {
            val process = GalaxyHabProcess(context.applicationContext, report = { message ->
                if (message.startsWith("HAB: submitted frames=")) worker.post {
                    if (!closed && epoch == generation) reconnect.stableFrames()
                }
                main.post { if (!closed && epoch == generation) report(message) }
            })
            if (closed || epoch != generation) { process.finish(); return }
            bridge = process
            pump(generation)
        } catch (error: Exception) { failed(generation, error) }
    }

    private fun pump(generation: Int) {
        if (closed || epoch != generation) return
        try {
            val pixels = frames.take()
            try {
                val now = SystemClock.elapsedRealtime()
                // 无新画面时仅1秒维持一次HAB会话，低于QNX 5秒失联预算；运动帧仍按100ms提交。
                if (pixels != null || now - lastWrittenAt >= 1000) {
                    bridge?.frame(pixels); lastWrittenAt = now
                    if (pixels != null) ++submittedCount
                }
            } finally { if (pixels != null) frames.release(pixels) }
            worker.postDelayed({ pump(generation) }, GalaxyHabLayout.FRAME_INTERVAL_MILLIS)
        } catch (error: Exception) { failed(generation, error) }
    }

    private fun captureFrame(source: ImageReader) {
        if (closed) return
        try {
            val image = source.acquireLatestImage() ?: return
            image.use {
                if (!active && lastCaptureAt > 0) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastCaptureAt < GalaxyHabLayout.FRAME_INTERVAL_MILLIS) return
                val pixels = frames.acquire() ?: return
                var published = false
                try {
                    val plane = image.planes.single()
                    val bytesPerRow = GalaxyHabLayout.WIDTH * 4
                    check(image.width == GalaxyHabLayout.WIDTH && image.height == GalaxyHabLayout.HEIGHT &&
                        plane.pixelStride == 4 && plane.rowStride >= bytesPerRow)
                    val buffer = plane.buffer
                    val start = buffer.position()
                    check(buffer.remaining() >= (GalaxyHabLayout.HEIGHT - 1) * plane.rowStride + bytesPerRow)
                    repeat(GalaxyHabLayout.HEIGHT) { row ->
                        buffer.position(start + row * plane.rowStride)
                        buffer.get(pixels, row * bytesPerRow, bytesPerRow)
                    }
                    frames.publish(pixels)
                    ++capturedCount
                    published = true
                    lastCaptureAt = now
                } finally { if (!published) frames.release(pixels) }
            }
        } catch (error: Exception) {
            main.post {
                if (!closed) { report("HAB: capture failed error=${error.javaClass.simpleName}"); close() }
            }
        }
    }

    private fun failed(generation: Int, error: Exception) {
        bridge?.finish(); bridge = null
        if (closed || epoch != generation) return
        val delay = reconnect.failed()
        main.post {
            if (!closed && epoch == generation)
                report("HAB: output failed stage=capture-or-pipe error=${error.javaClass.simpleName}" +
                    if (delay == null) " recovery=exhausted" else " retryMillis=$delay")
        }
        if (delay != null) worker.postDelayed({ connect(generation) }, delay)
    }

    fun setMapActive(active: Boolean) { presentation?.map?.visibility = if (active) View.VISIBLE else View.INVISIBLE }
    fun sendSurfaces() { presentation?.surfaces?.forEach { (role, surface) -> surfaceChanged(role, surface) } }

    fun metrics() = "captured=$capturedCount newFramesSubmitted=$submittedCount mailbox=${frames.metrics()}"

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        ++epoch
        bridge?.close()
        worker.removeCallbacksAndMessages(null)
        if (::reader.isInitialized) reader.setOnImageAvailableListener(null, null)
        runCatching { presentation?.dismiss() }; presentation = null
        display?.release(); display = null
        worker.post {
            bridge?.finish(); bridge = null
            thread.quitSafely()
        }
        capture.post {
            if (::reader.isInitialized) reader.close()
            frames.close()
            captureThread.quitSafely()
        }
    }

    private inner class CompositionWindow(context: Context, display: android.view.Display) : Presentation(context, display) {
        var map: TextureView? = null
        val surfaces = mutableMapOf<String, Surface>()
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
            fun layer(role: String, width: Int, height: Int): TextureView {
                val texture = TextureView(context).apply { isOpaque = role != AutoContract.THEME }
                root.addView(texture, FrameLayout.LayoutParams(-1, -1))
                texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(value: SurfaceTexture, w: Int, h: Int) {
                        value.setDefaultBufferSize(width, height)
                        if (closed) return
                        val surface = Surface(value)
                        surfaces[role] = surface
                        surfaceChanged(role, surface)
                    }
                    override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                        surfaceChanged(role, null)
                        surfaces.remove(role)?.release()
                        return true
                    }
                    override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
                }
                return texture
            }
            map = layer(AutoContract.MAP, GalaxyHabLayout.MAP_WIDTH, GalaxyHabLayout.MAP_HEIGHT)
            layer(AutoContract.THEME, GalaxyHabLayout.WIDTH, GalaxyHabLayout.HEIGHT)
            setContentView(root)
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
    }
}
