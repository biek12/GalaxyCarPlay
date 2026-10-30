package com.ecarx.autoservice.projection

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.hardware.HardwareBuffer
import com.galaxy.auto.contract.SceneConfiguration
import com.galaxy.auto.contract.ProjectionPolicy
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.galaxy.auto.contract.AutoContract
import java.io.Closeable
import com.ecarx.autoservice.settings.ServiceFeatures

/** 自有EGL合成器只负责合成，不把Android屏号映射成QNX屏号。
 * 主线程拥有合成生命周期/Surface；采集线程持续排空ImageReader，管道线程独占子进程。
 * 握手或写pipe阻塞时也不会持有Image阻塞RenderThread；3份RGBA邮箱只保留最新帧。
 */
internal class GalaxyHabComposition(
    private val context: Context,
    private val surfaceChanged: (String, Surface?) -> Unit,
    private val report: (String) -> Unit,
    private val prepareReceiver: ((Boolean) -> Unit) -> Unit,
    private val connectionChanged: (Boolean) -> Unit,
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("GalaxyPlay-hab-frames").apply { start() }
    private val worker = Handler(thread.looper)
    private val frameWake = GalaxyHabFrameWake(
        post = { task, delay -> worker.postDelayed(task, delay); Unit },
        remove = { task -> worker.removeCallbacks(task) },
    )
    private val captureThread = HandlerThread("GalaxyPlay-hab-capture").apply { start() }
    private val capture = Handler(captureThread.looper)
    private val frames = GalaxyHabFrames()
    private lateinit var reader: ImageReader
    private var lastCaptureAt = 0L
    private var lastWrittenAt = 0L
    @Volatile private var capturedCount = 0L
    @Volatile private var submittedCount = 0L
    @Volatile private var copyTotalMicros = 0L
    @Volatile private var copyMaxMicros = 0L
    @Volatile private var writeTotalMicros = 0L
    @Volatile private var writeMaxMicros = 0L
    @Volatile private var acquireToPipeMaxMicros = 0L
    private val copyTimings=GalaxyFrameTimings()
    private val postTimings=GalaxyFrameTimings()
    private val ageTimings=GalaxyFrameTimings()
    private val intervalTimings=GalaxyFrameTimings()
    private var lastSubmittedNanos=0L
    private var graphics: GalaxyGlComposition? = null
    @Volatile private var epoch = 0
    @Volatile private var closed = false
    @Volatile private var bridge: GalaxyHabProcess? = null
    @Volatile private var active = false
    private var reconnect = GalaxyHabReconnect()
    private var waitingForReceiver = false
    private var rootTransport = false

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        try {
            reader = ImageReader.newInstance(GalaxyHabLayout.WIDTH, GalaxyHabLayout.HEIGHT, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            reader.setOnImageAvailableListener({ source -> captureFrame(source) }, capture)
            // 首次创建自己的key，供授权测试工具在连接手机前配置自有QNX receiver。
            GalaxyHabKey.load(context).fill(0)
            graphics = GalaxyGlComposition(reader.surface, surfaceChanged, report)
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
        graphics?.setOutputActive(next)
        if (!next) setMapActive(false)
        val generation = ++epoch
        connectionChanged(false)
        frameWake.stop()
        bridge?.close()
        worker.removeCallbacksAndMessages(null)
        worker.post {
            try { bridge?.abortAndWait() } catch (_: GalaxyHabOwnerBusy) {
                main.post { if (!closed) report("HAB: sender release pending; no overlapping owner") }
                return@post
            }
            bridge = null
            waitingForReceiver = false
            if (!next || closed || epoch != generation) return@post
            reconnect = GalaxyHabReconnect()
            connect(generation)
        }
    }

    private fun current(generation: Int) = !closed && active && epoch == generation

    private fun open(generation: Int, root: Boolean): GalaxyHabProcess {
        check(current(generation)) { "hab_session_cancelled" }
        check(!root || ServiceFeatures.read(context).root) { "root_disabled" }
        val launch: (String) -> Process = if (root)
            { path -> com.ecarx.autoservice.platform.RootHabProcess(context, path) }
        else { path -> ProcessBuilder(path).redirectErrorStream(true).start() }
        val process = GalaxyHabProcess(context.applicationContext, report = { message ->
            if (message.startsWith("HAB: submitted frames=")) worker.post {
                if (current(generation)) reconnect.stableFrames()
            }
            main.post { if (current(generation)) report(message) }
        }, launch = launch)
        // 先登记再等待：主线程停止能够立即取消当前握手。
        bridge = process
        try {
            process.awaitReady()
            check(current(generation)) { "hab_session_cancelled" }
            rootTransport = root
            return process
        } catch (error: Exception) {
            process.abortAndWait(); bridge = null
            throw error
        }
    }

    private fun connected(generation: Int, prepared: Boolean = false) {
        val usedRoot = rootTransport
        val usedPreparation = prepared
        main.post {
            if (current(generation)) {
                connectionChanged(true)
                report("HAB: connected transport=${if (usedRoot) "root" else "app"} receiverPreparation=$usedPreparation")
            }
        }
        waitingForReceiver = false
        frameWake.start { pump(generation) }
        frameWake.wake()
    }

    private fun connect(generation: Int, prepared: Boolean = false) {
        if (!current(generation)) return
        try {
            if (prepared) {
                waitingForReceiver = false
                open(generation, true)
                connected(generation, prepared = true)
                return
            }
            val existing = GalaxyHabSessionStart({ root -> open(generation, root) }, { current(generation) }).existing()
            if (!current(generation)) return
            if (existing != null) { connected(generation); return }
            // 同一HAB协议的两种本地执行身份均未握手；此时才请求Root/QNX生命周期准备。
            waitingForReceiver = true
            main.post {
                if (current(generation)) prepareReceiver { ready ->
                    worker.post {
                        if (current(generation) && waitingForReceiver && ready) {
                            connect(generation, prepared = true)
                        }
                    }
                }
            }
        } catch (error: GalaxyHabOwnerBusy) {
            main.post { if (current(generation)) report("HAB: sender release pending; no overlapping owner") }
        } catch (error: Exception) { failed(generation, error) }
    }

    private fun pump(generation: Int) {
        if (closed || epoch != generation) return
        try {
            val frame = frames.takeStamped()
            val pixels = frame?.pixels
            try {
                val now = SystemClock.elapsedRealtime()
                // 无新画面时仅1秒维持一次HAB会话，低于QNX 5秒失联预算；运动帧由GL owner按当前策略生成。
                if (pixels != null || now - lastWrittenAt >= 1000) {
                    val started = SystemClock.elapsedRealtimeNanos()
                    bridge?.frame(pixels); lastWrittenAt = now
                    if (frame != null) {
                        val ended = SystemClock.elapsedRealtimeNanos()
                        val micros = (ended - started) / 1000
                        writeTotalMicros += micros; writeMaxMicros = maxOf(writeMaxMicros, micros)
                        acquireToPipeMaxMicros = maxOf(acquireToPipeMaxMicros, (ended - frame.acquiredAtNanos) / 1000)
                        copyFrameTiming(ended,frame.acquiredAtNanos,micros)
                        ++submittedCount
                    }
                }
            } finally { if (pixels != null) frames.release(pixels) }
            // 新帧由capture直接唤醒；无画面变化时才等待心跳，避免再等一个100ms轮询周期。
            frameWake.idle(1000 - (SystemClock.elapsedRealtime() - lastWrittenAt))
        } catch (error: Exception) { failed(generation, error) }
    }

    private fun captureFrame(source: ImageReader) {
        if (closed) return
        try {
            val image = source.acquireLatestImage() ?: return
            val acquired = SystemClock.elapsedRealtimeNanos()
            image.use {
                if (!active && lastCaptureAt > 0) return
                val now = SystemClock.elapsedRealtime()
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
                    frames.publish(pixels, acquired)
                    frameWake.wake()
                    val micros = (SystemClock.elapsedRealtimeNanos() - acquired) / 1000
                    copyTotalMicros += micros; copyMaxMicros = maxOf(copyMaxMicros, micros)
                    copyTimings.recordMicros(micros)
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
        com.ecarx.autoservice.ServiceTrace.event("hab_output_failed", error)
        frameWake.stop()
        val previous = bridge; bridge = null
        try { previous?.abortAndWait() } catch (_: GalaxyHabOwnerBusy) {
            main.post { if (current(generation)) { connectionChanged(false); report("HAB: sender release pending; no overlapping owner") } }
            return
        }
        if (closed || epoch != generation) return
        val delay = reconnect.failed()
        main.post {
            if (!closed && epoch == generation) {
                connectionChanged(false)
                report("HAB: output failed stage=capture-or-pipe error=${error.javaClass.simpleName}" +
                    if (delay == null) " recovery=exhausted" else " retryMillis=$delay")
            }
        }
        if (delay != null) worker.postDelayed({ connect(generation) }, delay)
    }

    fun setMapActive(active: Boolean) { graphics?.setMapActive(active) }
    /** Root关闭要结束正在使用UID0的输出；普通App已连通的会话继续，不因基础服务换代重连。 */
    fun rootPolicyChanged() {
        if (active && (rootTransport || waitingForReceiver)) {
            setSessionActive(false)
            setSessionActive(true)
        }
    }
    fun sendSurfaces() { graphics?.sendSurfaces() }
    fun configureScene(role: String, scene: SceneConfiguration) { graphics?.configure(role, scene) }
    fun clearScene(role: String) { graphics?.clearScene(role) }
    fun setPreviewSurface(surface: Surface?) { graphics?.setPreviewSurface(surface) }
    fun setPolicy(policy: ProjectionPolicy) { graphics?.setPolicy(policy) }

    private fun copyFrameTiming(ended: Long,acquired: Long,writeMicros: Long){
        postTimings.recordMicros(writeMicros);ageTimings.recordMicros((ended-acquired)/1000)
        if(lastSubmittedNanos>0)intervalTimings.recordMicros((ended-lastSubmittedNanos)/1000)
        lastSubmittedNanos=ended
    }

    // 自有POSTED确认表示Screen post调用成功返回；仍不等同于面板呈现/手机到仪表延迟。
    fun metrics() = "rootTransport=$rootTransport waitingReceiver=$waitingForReceiver captured=$capturedCount newFramesSubmitted=$submittedCount mailbox=${frames.metrics()}" +
        " graphics=${graphics?.metrics()} copyMicrosMean=${copyTotalMicros / capturedCount.coerceAtLeast(1)} copyMicrosMax=$copyMaxMicros" +
        " postAckMicrosMean=${writeTotalMicros / submittedCount.coerceAtLeast(1)} postAckMicrosMax=$writeMaxMicros acquireToPostAckMicrosMax=$acquireToPipeMaxMicros"+
        " ${copyTimings.metrics("copyUs")} ${postTimings.metrics("postAckUs")} ${ageTimings.metrics("frameAgeUs")} ${intervalTimings.metrics("postIntervalUs")}"

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        connectionChanged(false)
        ++epoch
        frameWake.stop()
        bridge?.close()
        worker.removeCallbacksAndMessages(null)
        if (::reader.isInitialized) reader.setOnImageAvailableListener(null, null)
        graphics?.close(); graphics = null
        for (role in listOf(AutoContract.MAP, AutoContract.THEME, AutoContract.PREVIEW)) surfaceChanged(role, null)
        worker.post {
            runCatching { bridge?.abortAndWait() }; bridge = null
            thread.quitSafely()
        }
        capture.post {
            if (::reader.isInitialized) reader.close()
            frames.close()
            captureThread.quitSafely()
        }
    }

}
