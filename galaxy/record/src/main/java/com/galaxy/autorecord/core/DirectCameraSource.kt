package com.galaxy.autorecord.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Camera2 直连采集（JPEG 通道，四路并发）。
 *
 * 为什么直连而不是经 AutoService 转发：CAMERA 若已授予（固件预授予或用户同意），
 * 转发 4 路 6 MB 级缓冲区纯属多余开销；用户明确要求"最少资源办最大的事"。
 * AutoService 的价值保留在**代为授权**与**能力扫描**两件事上，不参与每一帧。
 *
 * 设计要点（全部来自实测约束，见 CameraProfile）：
 * 1. 只用 JPEG：实测 `getOutputSizes(JPEG)` 只有 1280×800，其它格式未验证；
 * 2. **自驱动单次 capture**：一帧处理完才请求下一帧，HAL 不排队、内存不堆积；
 * 3. **自适应帧率**：实测单次抓拍首帧 268–478 ms，25 fps 连续推流未验证，
 *    所以按"目标帧率 + 实测间隔"退让，绝不硬追；实际帧率如实上报；
 * 4. **按 EOI 截断**：缓冲恒 6 MB，真实 JPEG 只有 0.55–0.83 MB；
 * 5. **降采样解码**：四宫格 inSampleSize=4（320×200 足够显示），单路放大才全尺寸；
 * 6. **并发发起、各自等待**：实测四路并发 0.5 s 内出首帧，不串行等待；
 * 7. 只在前台可见时开流，离开即释放（省电与相机占用）。
 */
class DirectCameraSource(
    context: Context,
    private val adapter: RecordCameraAdapter,
    /** 帧回调发生在相机线程，UI 侧需回主线程。 */
    private val onFrame: (direction: String, bitmap: Bitmap) -> Unit,
    private val onStatusChanged: () -> Unit,
) : CameraChannelSource {

    override val label: String get() = adapter.label
    override fun cameraId(direction: String): String? = routeCache[direction]?.id
    @Volatile private var routeCache = emptyMap<String, RecordCameraRoute>()

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val thread = HandlerThread("record-camera").apply { start() }
    private val handler = Handler(thread.looper)

    private val states = ConcurrentHashMap<String, ChannelStatus>()
    private val sessions = HashMap<String, DirectionSession>()

    @Volatile
    private var running = false
    @Volatile private var released = false

    @Volatile
    private var targetFps = DEFAULT_GRID_FPS

    @Volatile
    private var fullSizeDirection: String? = null

    init {
        routeCache = runCatching { adapter.routes() }.getOrDefault(emptyMap())
        resetStates("未开始预览")
    }

    // ---- 对外控制 ----

    /**
     * 开始预览。
     *
     * @param directions 需要出图的方向（四宫格=四路，单路=一路；单路时释放其余，省资源）
     * @param fullSizeDirection 需要全尺寸解码的方向（放大查看的那一路）
     */
    fun start(directions: List<String>, fullSizeDirection: String? = null) {
        if (released) return
        val wanted = directions.filter { it in SegmentLayout.DIRECTIONS }.distinct()
        if (wanted.isEmpty()) return
        targetFps = if (wanted.size == 1) DEFAULT_SINGLE_FPS else DEFAULT_GRID_FPS
        this.fullSizeDirection = fullSizeDirection
        running = true
        handler.post { sync(wanted) }
    }

    fun stop() {
        running = false
        handler.post { closeAll("预览已停止") }
    }

    /** 单路放大时切换全尺寸解码，不重开会话。 */
    fun setFullSizeDirection(direction: String?) {
        fullSizeDirection = direction
    }

    override fun statuses(): Map<String, ChannelStatus> = SegmentLayout.DIRECTIONS.associate { direction ->
        direction to (states[direction] ?: ChannelStatus(direction, CameraState.STOPPED, "未开始预览"))
    }

    override fun release() {
        if (released) return
        released = true
        running = false
        handler.post { closeAll("已释放") }
        thread.quitSafely()
    }

    // ---- 内部：会话同步 ----

    private fun sync(wanted: List<String>) {
        if (!running) return
        // 关掉不再需要的方向（单路放大时把另外三路放掉，避免无谓占用）
        sessions.filterKeys { it !in wanted }.keys.toList().forEach { closeDirection(it, "已切换到其他方向") }
        val routes = runCatching { adapter.routes() }.getOrElse {
            Log.w(TAG, "读取摄像头路由失败", it)
            resetStates("无法读取摄像头能力：${it.javaClass.simpleName}")
            return
        }
        if (routes.isEmpty()) {
            resetStates("没有可用摄像头路由，请检查标定与设备能力")
            return
        }
        routeCache = routes
        wanted.forEach { direction ->
            val route = routes[direction]
            if (route == null) setState(direction, CameraState.DISABLED, "当前适配器未配置此方向")
            else {
                sessions[direction]?.takeIf { it.cameraId != route.id || it.width != route.width || it.height != route.height }
                    ?.let { closeDirection(direction, "摄像头路由已变更") }
                if (sessions[direction] == null) openDirection(direction, route)
            }
        }
    }

    private fun openDirection(direction: String, route: RecordCameraRoute) {
        val cameraId = route.id
        val camera = manager
        if (camera == null) {
            setState(direction, CameraState.DISCONNECTED, "本机不支持 Camera2")
            return
        }
        val session = DirectionSession(direction, cameraId, route.width, route.height)
        sessions[direction] = session
        setState(direction, CameraState.WAITING_FIRST_FRAME, "打开 cam$cameraId…")
        session.firstFrameTimeout = Runnable {
            if (isCurrent(session) && session.frames == 0L) failSession(session, CameraState.DISCONNECTED, "等待首帧超时，请检查相机占用或输入")
        }.also { handler.postDelayed(it, 8000) }
        try {
            // 并发发起、各自等待：实测四路可同时 open，不等上一路 onOpened
            camera.openCamera(
                session.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (!isCurrent(session)) {
                            device.close()
                            return
                        }
                        session.device = device
                        createReaderAndSession(session)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        failSession(session, CameraState.DISCONNECTED, "相机被断开（可能被原厂环视抢占）")
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        failSession(session, CameraState.DISCONNECTED, "打开失败：CameraDevice 错误码 $error")
                    }
                },
                handler,
            )
        } catch (e: CameraAccessException) {
            Log.w(TAG, "打开失败 ${direction} cam$cameraId reason=${e.reason}", e)
            // 权限/占用类错误必须原样报出，不吞成"未知错误"
            failSession(session, CameraState.NO_PERMISSION, "无权限或相机不可用：${e.reason} ${e.message ?: ""}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "HAL 拒绝 ${direction} cam$cameraId", e)
            // 实测 4–50 就是这条：Illegal argument to HAL module for camera "N"
            failSession(session, CameraState.DISCONNECTED, "HAL 拒绝该 cameraId：${e.message ?: e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            Log.w(TAG, "相机权限不足 ${session.direction}", e)
            failSession(session, CameraState.NO_PERMISSION, "缺少 CAMERA 权限：${e.message ?: ""}")
        } catch (e: Throwable) {
            Log.w(TAG, "打开相机异常 ${session.direction}", e)
            failSession(session, CameraState.DISCONNECTED, "打开异常：${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    private fun createReaderAndSession(session: DirectionSession) {
        val device = session.device ?: return
        try {
            val reader = ImageReader.newInstance(session.width, session.height, ImageFormat.JPEG, 2)
            session.reader = reader
            reader.setOnImageAvailableListener({ r -> handleImage(session, r) }, handler)
            device.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (!isCurrent(session)) {
                            configured.close()
                            return
                        }
                        session.captureSession = configured
                        captureNext(session)
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        failed.close()
                        failSession(session, CameraState.DISCONNECTED, "创建采集会话失败")
                    }
                },
                handler,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "创建采集会话失败 ${session.direction}", e)
            failSession(session, CameraState.DISCONNECTED, "创建会话异常：${e.javaClass.simpleName}")
        }
    }

    /** 一帧处理完再请求下一帧：不排队、不堆积，帧率自然退让到本机能力。 */
    private fun captureNext(session: DirectionSession) {
        if (!isCurrent(session)) return
        val device = session.device ?: return
        val captureSession = session.captureSession ?: return
        val reader = session.reader ?: return
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
            }.build()
            session.lastRequestAt = android.os.SystemClock.elapsedRealtime()
            captureSession.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(source: CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    failSession(session, CameraState.DISCONNECTED, "采集失败：${failure.reason}")
                }
            }, handler)
        } catch (e: Throwable) {
            Log.w(TAG, "取帧请求失败 ${session.direction}", e)
            failSession(session, CameraState.DISCONNECTED, "取帧请求失败：${e.javaClass.simpleName}")
        }
    }

    private fun handleImage(session: DirectionSession, reader: ImageReader) {
        if (!isCurrent(session)) return
        val startedAt = System.currentTimeMillis()
        val bitmap = try {
            val image = reader.acquireNextImage() ?: return
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val validLength = CameraProfile.jpegEndOffset(bytes)
                if (validLength <= 0) {
                    // 坏帧：不计入已出帧，也不把 6 MB 垃圾当图
                    session.badFrames++
                    null
                } else {
                    session.validBytes = validLength
                    decode(bytes, validLength, sampleSizeFor(session.direction))
                }
            } finally {
                image.close()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "取帧失败 ${session.direction}", e)
            null
        }

        if (!isCurrent(session)) {
            bitmap?.recycle()
            return
        }
        if (bitmap != null) {
            val now = System.currentTimeMillis()
            session.frames++
            if (session.lastFrameAt > 0L) {
                val delta = (now - session.lastFrameAt).coerceAtLeast(1L)
                val instant = 1000.0 / delta
                session.fps = if (session.fps <= 0.0) instant else session.fps * 0.8 + instant * 0.2
            }
            session.lastFrameAt = now
            val fpsText = String.format(java.util.Locale.US, "%.1f fps · %d KB", session.fps, session.validBytes / 1024)
            if (session.frames == 1L) {
                session.firstFrameTimeout?.let(handler::removeCallbacks)
                session.firstFrameTimeout = null
                Log.i(
                    TAG,
                    "首帧 ${session.direction} cam${session.cameraId} ${bitmap.width}x${bitmap.height} " +
                        "${session.validBytes}B 耗时 ${now - session.openedAt}ms",
                )
                session.lastFpsLogAt = now
                setState(session.direction, CameraState.ACTIVE, fpsText)
            } else {
                // 帧率只刷新状态描述，不触发 onStatusChanged（否则每帧都在重建 UI）
                states[session.direction] = ChannelStatus(session.direction, CameraState.ACTIVE, fpsText)
                if (now - session.lastFpsLogAt >= 5000L) {
                    session.lastFpsLogAt = now
                    Log.i(TAG, "帧率 ${session.direction} cam${session.cameraId} $fpsText 累计 ${session.frames} 帧 坏帧 ${session.badFrames}")
                }
            }
            onFrame(session.direction, bitmap)
        }

        // 自适应间隔：目标间隔内没耗时到就补等待，避免把 CPU/HAL 打满
        val interval = 1000L / targetFps.coerceAtLeast(1)
        val spent = android.os.SystemClock.elapsedRealtime() - session.lastRequestAt
        val delay = (interval - spent).coerceAtLeast(0L)
        if (delay > 0L) handler.postDelayed({ captureNext(session) }, delay) else captureNext(session)
    }

    /** 四宫格用 1/4 采样（320×200），单路放大才全量解码——这是"最少资源"的关键一处。 */
    private fun sampleSizeFor(direction: String): Int =
        if (fullSizeDirection == direction) 1 else GRID_SAMPLE_SIZE

    private fun decode(bytes: ByteArray, length: Int, sample: Int): Bitmap? = runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, length, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }.getOrNull()

    private fun closeDirection(direction: String, reason: String) {
        val session = sessions.remove(direction) ?: return
        session.closed = true
        session.firstFrameTimeout?.let(handler::removeCallbacks)
        session.firstFrameTimeout = null
        runCatching { session.captureSession?.close() }
        runCatching { session.device?.close() }
        runCatching { session.reader?.close() }
        setState(direction, CameraState.STOPPED, reason)
    }

    /** 迟到的旧回调不能移除同方向的新会话；错误与断开同样释放 reader/device/session。 */
    private fun isCurrent(session: DirectionSession): Boolean =
        running && !released && !session.closed && sessions[session.direction] === session

    private fun failSession(session: DirectionSession, state: CameraState, detail: String) {
        if (sessions[session.direction] !== session || session.closed) return
        closeDirection(session.direction, detail)
        setState(session.direction, state, detail)
    }

    private fun closeAll(reason: String) {
        sessions.keys.toList().forEach { closeDirection(it, reason) }
        resetStates(reason)
    }

    private fun setState(direction: String, state: CameraState, detail: String) {
        val previous = states[direction]
        val next = ChannelStatus(direction, state, detail)
        states[direction] = next
        if (previous != next) onStatusChanged()
    }

    private fun resetStates(reason: String) {
        SegmentLayout.DIRECTIONS.forEach { direction ->
            states[direction] = ChannelStatus(direction, CameraState.STOPPED, reason)
        }
        onStatusChanged()
    }

    /** 单路会话：只在本类的工作线程里读写。 */
    private class DirectionSession(val direction: String, val cameraId: String, val width: Int, val height: Int) {
        var device: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var closed = false
        var frames = 0L
        var badFrames = 0L
        var firstFrameTimeout: Runnable? = null
        var validBytes = 0
        var lastRequestAt = 0L
        var fps = 0.0
        var lastFrameAt = 0L
        var lastFpsLogAt = 0L
        val openedAt = System.currentTimeMillis()
    }

    companion object {
        private const val TAG = "AutoRecordCamera"

        /** 四宫格每路 8fps：四路合计 32 帧/秒，足够看画面，不至于烧 HAL 与解码。 */
        const val DEFAULT_GRID_FPS = 8

        /** 单路放大 15fps：只有一路在跑，可以更流畅；仍低于实测 25fps 上限。 */
        const val DEFAULT_SINGLE_FPS = 15

        /** 四宫格解码降采样倍数（1280×800 → 320×200）。 */
        const val GRID_SAMPLE_SIZE = 4
    }
}
