package com.galaxy.autorecord.core

import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap

/**
 * L6 PRIVATE 连续预览：HAL→SurfaceView，不经 JPEG 压缩、6MB 回读和 Bitmap 解码。
 * Surface 由页面持有；本类只持有借用引用。会话/请求只在相机线程操作，迟到回调按会话身份丢弃。
 *
 * 录制（B3）：每路可再绑一个编码器输入 Surface（[bindRecordSurface]），会话输出＝预览+录制，
 * 相机帧时间戳（SENSOR_TIMESTAMP）由 HAL 直接写入图形缓冲，编码 PTS 即真实帧时间。
 * 换段时只换录制 Surface（会话重配），预览不中断更久于一次重配。
 */
class SurfaceCameraSource(context: Context, private val adapter: RecordCameraAdapter,
    private val changed: () -> Unit) : CameraChannelSource, RecordSurfaceBinder {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("record-preview").apply { start() }
    private val handler = Handler(thread.looper)
    private val surfaces = mutableMapOf<String, Surface>()
    private val recordSurfaces = mutableMapOf<String, Surface>()
    private val sessions = mutableMapOf<String, Session>()
    private val states = ConcurrentHashMap<String, ChannelStatus>()
    @Volatile private var routes = emptyMap<String, RecordCameraRoute>()
    @Volatile private var running = false
    @Volatile private var released = false
    private var wanted = emptyList<String>()
    override val label = "L6 Camera2 PRIVATE 连续预览"
    override fun cameraId(direction: String) = routes[direction]?.id
    override fun statuses() = SegmentLayout.DIRECTIONS.associateWith {
        states[it] ?: ChannelStatus(it, CameraState.STOPPED, "未开始预览")
    }

    fun bind(direction: String, surface: Surface?, previous: Surface? = null) {
        if (released) return
        handler.post {
            if (surface == null && previous != null && surfaces[direction] !== previous) return@post
            if (surfaces[direction] === surface) return@post
            close(direction)
            if (surface == null) surfaces.remove(direction) else surfaces[direction] = surface
            if (running) sync()
        }
    }

    /** 绑定/解绑编码器输入 Surface（录制引擎调用）；换 Surface 即重配会话。 */
    override fun bindRecordSurface(direction: String, surface: Surface?, previous: Surface?) {
        if (released) return
        handler.post {
            if (surface == null && previous != null && recordSurfaces[direction] !== previous) return@post
            if (recordSurfaces[direction] === surface) return@post
            Log.i(TAG, "绑定录制Surface $direction valid=${surface?.isValid}（换会话）")
            close(direction)
            if (surface == null) recordSurfaces.remove(direction) else recordSurfaces[direction] = surface
            if (running) sync()
        }
    }
    fun start(directions: List<String>) {
        if (released) return
        running = true
        handler.post { wanted = directions.filter { it in SegmentLayout.DIRECTIONS }.distinct(); sync() }
    }
    fun stop() {
        running = false
        handler.post { sessions.keys.toList().forEach(::close) }
    }
    override fun release() {
        if (released) return
        released = true; running = false
        handler.post { sessions.keys.toList().forEach(::close); surfaces.clear() }
        thread.quitSafely()
    }
    /** 统计生产帧；SurfaceFlinger呈现与截图另行验收，不把回调当显示验收。 */
    private fun produced(session: Session) {
            if (!current(session)) return
            val direction = session.direction
            val at = SystemClock.elapsedRealtime()
            session.frames++
            if (session.frames == 1L) {
                handler.removeCallbacks(session.timeout)
                session.windowAt = at
                state(direction, CameraState.ACTIVE, "连续预览")
                Log.i(TAG, "首帧 $direction cam${session.route.id} PRIVATE ${session.route.width}x${session.route.height} ${at-session.openedAt}ms")
            }
            if (at-session.windowAt >= 5000) {
                val fps = (session.frames-session.windowFrames)*1000.0/(at-session.windowAt)
                val detail = String.format(java.util.Locale.US, "%.1f fps · PRIVATE", fps)
                states[direction] = ChannelStatus(direction, CameraState.ACTIVE, detail)
                Log.i(TAG, "产出帧率 $direction cam${session.route.id} $detail 累计 ${session.frames}")
                session.windowAt = at; session.windowFrames = session.frames
            }
    }
    private fun sync() {
        if (!running || released) return
        sessions.keys.filter { it !in wanted }.toList().forEach(::close)
        routes = runCatching { adapter.routes() }.getOrElse {
            wanted.forEach { state(it, CameraState.DISCONNECTED, "相机路由读取失败") }; return
        }
        wanted.forEach { direction ->
            val route = routes[direction]
            val outputs = listOfNotNull(surfaces[direction], recordSurfaces[direction]).filter { it.isValid }
            if (route == null) state(direction, CameraState.DISABLED, "未配置摄像头")
            else if (outputs.isEmpty()) state(direction, CameraState.WAITING_FIRST_FRAME, "等待预览窗口")
            else {
                sessions[direction]?.takeIf { it.route != route || it.outputs != outputs }?.let { close(direction) }
                if (sessions[direction] == null) open(direction, route, outputs)
            }
        }
    }
    private fun open(direction: String, route: RecordCameraRoute, outputs: List<Surface>) {
        val session = Session(direction, route, outputs)
        sessions[direction] = session
        state(direction, CameraState.WAITING_FIRST_FRAME, "打开连续预览 cam${route.id}…")
        Log.i(TAG, "会话输出 $direction cam${route.id} 数量=${outputs.size}（preview=${surfaces[direction] != null} record=${recordSurfaces[direction] != null}）")
        session.timeout = Runnable { if (current(session) && session.frames == 0L) fail(session, "连续预览首帧超时") }
        handler.postDelayed(session.timeout, 8000)
        try {
            val configs = manager.getCameraCharacteristics(route.id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            check(configs?.getOutputSizes(android.graphics.ImageFormat.PRIVATE)?.any { it.width==route.width && it.height==route.height } == true) { "private_size_unsupported" }
            manager.openCamera(route.id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!current(session)) { device.close(); return }
                    session.device = device
                    configure(session, device)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); fail(session, "相机断开或被原厂占用") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); fail(session, "相机错误 $error") }
            }, handler)
        } catch (error: Exception) { fail(session, "连续预览打开失败", error) }
    }
    private fun configure(session: Session, device: CameraDevice) {
        try {
            device.createCaptureSession(session.outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(capture: CameraCaptureSession) {
                    if (!current(session)) { capture.close(); return }
                    session.capture = capture
                    try {
                        val template = if (session.outputs.size > 1) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                        val request = device.createCaptureRequest(template).apply {
                            session.outputs.forEach { addTarget(it) }
                        }.build()
                        capture.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                produced(session)
                            }
                            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                                fail(session, "连续采集失败 ${f.reason}")
                            }
                            override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frameNumber: Long) {
                                if (session.lostLogged < 6) {
                                    session.lostLogged++
                                    Log.w(TAG, "输出缓冲丢失 ${session.direction} target#${session.outputs.indexOf(target)} frame=$frameNumber")
                                }
                            }
                        }, handler)
                    } catch (error: Exception) { fail(session, "启动连续请求失败", error) }
                }
                override fun onConfigureFailed(capture: CameraCaptureSession) { capture.close(); fail(session, "连续预览会话配置失败") }
            }, handler)
        } catch (error: Exception) { fail(session, "连续预览会话创建失败", error) }
    }
    private fun current(session: Session) = running && !released && sessions[session.direction] === session
    private fun fail(session: Session, reason: String, error: Exception? = null) {
        if (sessions[session.direction] !== session) return
        Log.w(TAG, "$reason ${session.direction} cam${session.route.id}", error)
        close(session.direction)
        state(session.direction, CameraState.DISCONNECTED, reason + (error?.let { "：${it.javaClass.simpleName}" } ?: ""))
    }
    private fun close(direction: String) {
        val session = sessions.remove(direction) ?: return
        handler.removeCallbacks(session.timeout)
        runCatching { session.capture?.close() }; runCatching { session.device?.close() }
        state(direction, CameraState.STOPPED, "预览已停止")
    }
    private fun state(direction: String, state: CameraState, detail: String) {
        val next = ChannelStatus(direction, state, detail)
        if (states.put(direction, next) != next) changed()
    }
    private class Session(val direction: String, val route: RecordCameraRoute, val outputs: List<Surface>) {
        var device: CameraDevice? = null
        var capture: CameraCaptureSession? = null
        lateinit var timeout: Runnable
        val openedAt = SystemClock.elapsedRealtime()
        var frames = 0L
        var windowAt = 0L
        var windowFrames = 0L
        var lostLogged = 0
    }
    companion object { private const val TAG = "AutoRecordCamera" }
}
