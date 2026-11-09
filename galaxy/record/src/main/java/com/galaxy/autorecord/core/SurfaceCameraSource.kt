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
 * L6 Camera2 PRIVATE → OES/EGL 预览分发；页面与转向窗借用输出 Surface，不经 JPEG/Bitmap。
 * HAL 录制目标独立，预览窗口切换不改变稳定输入；连续分段由编码层完成。
 * 相机线程串行管理会话，直到 CameraDevice.onClosed 才释放仍可能被 HAL 使用的输入。
 */
class SurfaceCameraSource(private val context: Context, private val adapter: RecordCameraAdapter,
    private val changed: () -> Unit) : CameraChannelSource, RecordSurfaceBinder {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("record-preview").apply { start() }
    private val handler = Handler(thread.looper)
    private val surfaces = mutableMapOf<String, Surface>()
    private val auxiliary = mutableMapOf<String, Surface>()
    private val auxiliaryFrames=mutableMapOf<String,()->Unit>()
    private val webFlatSurfaces=mutableMapOf<String,Surface>()
    private val webSurfaces=mutableMapOf<String,Surface>()
    private var flat = false
    private val profiles = mutableMapOf<String, com.galaxy.autorecord.preview.LensProfile?>()
    private var gpuErrorAt=-60_000L
    private val distributor by lazy { com.galaxy.autorecord.preview.PreviewDistributor(handler) { error ->
        val now=SystemClock.elapsedRealtime()
        if(now-gpuErrorAt>=60_000){gpuErrorAt=now;RecordDiagnostics.event("preview_gpu",error)}
    } }
    private var distributed = false
    private val recordSurfaces = mutableMapOf<String, Surface>()
    private val sessions = mutableMapOf<String, Session>()
    private val closing = mutableSetOf<Session>()
    private data class Detach(val surfaces: Collection<Surface>, val completed: (Result<Unit>) -> Unit)
    private val detaches = mutableListOf<Detach>()
    private val idleWaiters=mutableListOf<()->Unit>()
    fun awaitIdle(completed:()->Unit){handler.post{idleWaiters.add(completed);completeDetaches()}}
    private val states = ConcurrentHashMap<String, ChannelStatus>()
    @Volatile private var routes = emptyMap<String, RecordCameraRoute>()
    @Volatile private var running = false
    @Volatile private var suspended=false
    fun suspendForPower(value:Boolean){if(suspended==value)return;suspended=value;handler.post{sync()}}
    @Volatile private var released = false
    @Volatile private var cameraClosed = false
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
            if (surface == null) surfaces.remove(direction) else surfaces[direction] = surface
            sync()
        }
    }

    fun setFlat(value:Boolean) { handler.post { flat=value;distributor.flat(value) } }
    override fun lensProfile(direction:String):com.galaxy.autorecord.preview.LensProfile? {
        val route=runCatching { adapter.routes()[direction] }.getOrNull()?:return null
        return com.galaxy.autorecord.preview.LensProfile.read(context,direction,route.id,route.width,route.height)
    }
    fun flatAvailable(direction:String):Boolean {
        return lensProfile(direction)!=null
    }
    /** 转向窗与页面共用一次相机采集，不新增第三路 HAL 输出。 */
    fun bindAssist(direction:String,surface:Surface?,previous:Surface?=null,frame:()->Unit={}) {
        if(released)return
        if(surface!=null&&!flatAvailable(direction))return
        handler.post { if(surface==null&&previous!=null&&auxiliary[direction]!==previous)return@post; if(surface==null){auxiliary.remove(direction);auxiliaryFrames.remove(direction)}else{auxiliary[direction]=surface;auxiliaryFrames[direction]=frame};sync() }
    }

    fun detachWeb(direction:String,surface:Surface,flat:Boolean=false,done:()->Unit){
        if(cameraClosed){done();return}
        handler.post{val targets=if(flat)webFlatSurfaces else webSurfaces;if(targets[direction]===surface){targets.remove(direction);if(distributed)distributor.bind(direction,if(flat)"web-flat" else "web",null);sync()};done()}
    }
    fun bindWeb(direction:String,surface:Surface?,flat:Boolean=false){
        if(released)return
        handler.post{val targets=if(flat)webFlatSurfaces else webSurfaces;if(surface==null)targets.remove(direction)else targets[direction]=surface;sync()}
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
            sync()
        }
    }
    override fun detachRecordSurfaces(surfaces: Map<String, Surface>, completed: (Result<Unit>) -> Unit) {
        if (cameraClosed) { completed(Result.success(Unit)); return }
        if (!handler.post {
            detaches.add(Detach(surfaces.values.toList(), completed))
            surfaces.forEach { (direction, surface) ->
                if (recordSurfaces[direction] === surface) recordSurfaces.remove(direction)
            }
            sessions.values.filter { session -> session.outputs.any { output -> surfaces.values.any { it === output } } }
                .toList().forEach { close(it.direction) }
            completeDetaches()
            sync()
        }) completed(if (cameraClosed) Result.success(Unit) else Result.failure(IllegalStateException("camera_thread_stopped")))
    }

    private fun completeDetaches() {
        if(sessions.isEmpty()&&closing.isEmpty()&&recordSurfaces.isEmpty()&&!running&&auxiliary.isEmpty()&&webSurfaces.isEmpty()&&webFlatSurfaces.isEmpty()){idleWaiters.toList().also{idleWaiters.clear()}.forEach{it()}}
        val ready = detaches.filter { detach ->
            (sessions.values + closing).none { session -> session.outputs.any { output -> detach.surfaces.any { it === output } } }
        }
        detaches.removeAll(ready.toSet())
        ready.forEach { it.completed(Result.success(Unit)) }
        if(!released && !running && recordSurfaces.isEmpty() && auxiliary.isEmpty()&&webSurfaces.isEmpty()&&webFlatSurfaces.isEmpty() && sessions.isEmpty() && closing.isEmpty() && distributed) {
            distributor.release();distributed=false
        }
        if (released && closing.isEmpty() && sessions.isEmpty()) {
            cameraClosed = true
            if(distributed)distributor.release()
            thread.quitSafely()
        }
    }

    private fun deviceClosed(session: Session) {
        closing.remove(session)
        completeDetaches()
        if (session.resumeAfterClose) sync()
    }

    fun start(directions: List<String>) {
        if (released) return
        running = true
        handler.post { wanted = directions.filter { it in SegmentLayout.DIRECTIONS }.distinct(); sync() }
    }
    fun stop() {
        running = false
        handler.post { sync() }
    }
    override fun release() {
        if (released) return
        released = true; running = false
        handler.post {
            sessions.keys.toList().forEach(::close)
            surfaces.clear(); auxiliary.clear(); auxiliaryFrames.clear();webSurfaces.clear();webFlatSurfaces.clear(); recordSurfaces.clear()
            completeDetaches()
        }
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
        if (released) return
        val needed=if(suspended)emptyList()else ((if(running)wanted else emptyList())+recordSurfaces.keys+auxiliary.keys+webSurfaces.keys+webFlatSurfaces.keys).distinct()
        sessions.keys.filter { it !in needed }.toList().forEach(::close)
        if(needed.isEmpty()){completeDetaches();return}
        routes = runCatching { adapter.routes() }.getOrElse {
            needed.forEach { state(it, CameraState.DISCONNECTED, "相机路由读取失败") }; return
        }
        needed.forEach { direction ->
            val route = routes[direction]
            val preview = if(route!=null && ((running && direction in wanted && surfaces[direction]?.isValid==true) || auxiliary[direction]?.isValid==true || recordSurfaces[direction]?.isValid==true || webSurfaces[direction]?.isValid==true || webFlatSurfaces[direction]?.isValid==true)) {
                runCatching {
                    val key="${route.id}:${route.width}:${route.height}"
                    if(!profiles.containsKey(key))profiles[key]=com.galaxy.autorecord.preview.LensProfile.read(context,direction,route.id,route.width,route.height)
                    distributed=true
                    val input=distributor.input(direction,route.width,route.height,profiles[key])
                    distributor.bind(direction,"page",surfaces[direction]?.takeIf { running && direction in wanted },flat)
                    distributor.bind(direction,"assist",auxiliary[direction],true,auxiliaryFrames[direction]?:{})
                    distributor.bind(direction,"web",webSurfaces[direction],false)
                    distributor.bind(direction,"web-flat",webFlatSurfaces[direction],true)
                    input
                }.getOrElse { RecordDiagnostics.event("preview_prepare",it);null }
            } else { if(distributed){distributor.bind(direction,"page",null);distributor.bind(direction,"assist",null);distributor.bind(direction,"web",null);distributor.bind(direction,"web-flat",null)};null }
            val outputs = listOfNotNull(preview, recordSurfaces[direction]).filter { it.isValid }
            if (route == null) state(direction, CameraState.DISABLED, "未配置摄像头")
            else if (outputs.isEmpty()) state(direction, CameraState.WAITING_FIRST_FRAME, "等待预览窗口")
            else {
                sessions[direction]?.takeIf { it.route != route || it.outputs != outputs }?.let { close(direction) }
                if (sessions[direction] == null && closing.none { it.direction == direction }) open(direction, route, outputs)
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
                    session.opening = false
                    session.device = device
                    if (!current(session)) { device.close(); return }
                    configure(session, device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    session.opening = false; session.device = device
                    device.close(); fail(session, "相机断开或被原厂占用")
                }
                override fun onError(device: CameraDevice, error: Int) {
                    session.opening = false; session.device = device
                    device.close(); fail(session, "相机错误 $error")
                }
                override fun onClosed(device: CameraDevice) { deviceClosed(session) }
            }, handler)
        } catch (error: Exception) {
            session.opening = false
            fail(session, "连续预览打开失败", error)
        }
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
                                session.lostTotal++
                                val now=SystemClock.elapsedRealtime()
                                if(now-session.lastLossReport>=60_000L) {
                                    session.lastLossReport=now
                                    RecordDiagnostics.event("camera_buffer_lost_${session.direction}",IllegalStateException("camera_output_buffer_lost"),
                                        mapOf("direction" to session.direction,"count" to session.lostTotal,
                                            "state" to if(target===recordSurfaces[session.direction]) "record" else "preview",
                                            "method" to "onCaptureBufferLost"))
                                }
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
    private fun current(session: Session) = !released && sessions[session.direction] === session &&
        ((running && session.direction in wanted) || session.direction in recordSurfaces || session.direction in auxiliary)
    private fun fail(session: Session, reason: String, error: Exception? = null) {
        if (sessions[session.direction] !== session) return
        Log.w(TAG, "$reason ${session.direction} cam${session.route.id}", error)
        RecordDiagnostics.event("camera_failure_${session.direction}",error?:IllegalStateException(reason),
            mapOf("direction" to session.direction,"caller" to "Camera2:${session.route.id}","stage" to reason))
        close(session.direction, resume = false)
        state(session.direction, CameraState.DISCONNECTED, reason + (error?.let { "：${it.javaClass.simpleName}" } ?: ""))
    }
    private fun close(direction: String) = close(direction, resume = true)
    private fun close(direction: String, resume: Boolean) {
        val session = sessions.remove(direction) ?: return
        session.resumeAfterClose = resume
        handler.removeCallbacks(session.timeout)
        closing.add(session)
        runCatching { session.capture?.close() }
        // close 返回不等于 HAL 已停止使用输出；直到 onClosed 才允许 Reader 释放/同路重开。
        runCatching { session.device?.close() }.onFailure { error ->
            Log.e(TAG, "相机关闭失败 $direction", error)
        }
        if (session.device == null && !session.opening) deviceClosed(session)
        state(direction, CameraState.STOPPED, "预览已停止")
    }
    private fun state(direction: String, state: CameraState, detail: String) {
        val next = ChannelStatus(direction, state, detail)
        if (states.put(direction, next) != next) changed()
    }
    private class Session(val direction: String, val route: RecordCameraRoute, val outputs: List<Surface>) {
        var resumeAfterClose = false
        var opening = true
        var device: CameraDevice? = null
        var capture: CameraCaptureSession? = null
        lateinit var timeout: Runnable
        val openedAt = SystemClock.elapsedRealtime()
        var frames = 0L
        var windowAt = 0L
        var windowFrames = 0L
        var lostLogged = 0
        var lostTotal=0L
        var lastLossReport=-60_000L
    }
    companion object { private const val TAG = "AutoRecordCamera" }
}
