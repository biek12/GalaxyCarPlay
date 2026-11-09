package com.galaxy.autorecord.core

import java.util.concurrent.Executor

/**
 * 单路摄像头状态（方向 → 状态 + 可读原因）。
 *
 * 这里只定义通道状态；当前获 CAMERA 权限后由 [DirectCameraSource] 直连采集，
 * 未授权时由 [UnavailableCameraSource] 明确报告原因。车辆数据仍只经 AutoService。
 */
data class ChannelStatus(
    val direction: String,
    val state: CameraState,
    val detail: String = "",
) {
    val isLive: Boolean get() = state == CameraState.ACTIVE
}

interface CameraChannelSource {
    /** 诊断页显示用，例如"AutoService（IAutoCamera）"。 */
    val label: String
    fun cameraId(direction: String): String? = null
    fun lensProfile(direction:String):com.galaxy.autorecord.preview.LensProfile? = null

    fun statuses(): Map<String, ChannelStatus>

    fun release()
}

/** 未接入实现：四路都报 DISCONNECTED 并给出确切原因，绝不伪造"等待首帧"之类看起来在工作的状态。 */
class UnavailableCameraSource(
    private val reason: String = "摄像头通道未接入（AutoService 侧 IAutoCamera 未落地）",
) : CameraChannelSource {
    override val label: String = "未接入"

    override fun statuses(): Map<String, ChannelStatus> =
        SegmentLayout.DIRECTIONS.associateWith { ChannelStatus(it, CameraState.DISCONNECTED, reason) }

    override fun release() = Unit
}

/** 预检失败项：必须能定位到方向/阶段/原因（规格 §14.2）。 */
data class PreflightFailure(
    /** null 表示与具体方向无关（存储、编码器、引擎） */
    val direction: String?,
    val stage: String,
    val detail: String,
)

sealed interface PreflightOutcome {
    /** 全部通过；[directions] 是实际可录方向。 */
    data class Passed(val directions: List<String>) : PreflightOutcome

    /** 有失败项。界面必须展示 [failures] 并让用户显式选择，不允许自动降级。 */
    data class Failed(val failures: List<PreflightFailure>) : PreflightOutcome

    /** 用户取消或预检被新的请求取代。 */
    data object Cancelled : PreflightOutcome
}

/**
 * 录制环境探测：把"能不能开始"的判断从界面里拿掉，放到可替换的实现里。
 * 真机实现用 [StorageVolumes] + [CodecCapabilities] + [CameraChannelSource]，单测用假实现。
 */
interface RecordingEnvironment {
    fun checkStorage(settings: RecordSettings): PreflightFailure?
    fun checkEncoder(settings: RecordSettings): PreflightFailure?

    /** 方位标定是否可用。默认放行，真机实现会检查 cameraId 映射。 */
    fun checkCameraLayout(): PreflightFailure? = null

    fun channels(): Map<String, ChannelStatus>

    /** 录制引擎是否已实现。挂上真实引擎（B3）后由宿主传 true，这是预检的最后一道门。 */
    val engineAvailable: Boolean
}

/** 真实录制引擎（B3）回调：全部在引擎线程产生，控制器负责转到 callbackExecutor。 */
interface RecordingEngineListener {
    /** 段事务已建（writing 清单已落盘）。 */
    fun onSegmentStarted(index: Int, segmentDir: java.io.File)

    /** 首批编码帧已写盘（只认"出帧+写盘"，编码器创建成功不算录制成功）。 */
    fun onRecordingActive()

    /** 一段收尾完成（EOS→drain→muxer.stop→fsync→改名→清单）。 */
    fun onSegmentFinished(index: Int, outcome: com.galaxy.autorecord.storage.FinishOutcome)

    /** 进度：当前段已写字节、段内已录毫秒、段号。 */
    fun onProgress(bytesWritten: Long, segmentElapsedMs: Long, segmentIndex: Int)

    /** 引擎停止（error 非空＝异常终止，界面须显示原因；空＝用户主动停止）。 */
    fun onStopped(error: String?)
}

/** 录制引擎端口：控制器只依赖这个接口，真实实现是 recording.RecordingEngine。 */
interface RecordingEnginePort {
    fun start(directions: List<String>, settings: RecordSettings, listener: RecordingEngineListener)
    fun requestStop()
    fun release()
}

/** 相机录制 Surface 绑定出口：引擎把编码器输入 Surface 交给相机源（会话重配由相机源负责）。 */
interface RecordSurfaceBinder {
    fun bindRecordSurface(direction: String, surface: android.view.Surface?, previous: android.view.Surface? = null)

    /** 完成回执只在所有使用这些 Surface 的旧设备 onClosed 后发出；调用方才可关闭 Reader。 */
    fun detachRecordSurfaces(surfaces: Map<String, android.view.Surface>, completed: (Result<Unit>) -> Unit)
}

/**
 * 录制状态机 + 两阶段预检（规格 v1.1 §14.2）。
 *
 * 阶段 1（预检）：连接 → 四路通道 → 编码器 → 存储 → 引擎，任一项失败即失败，
 * **不自动进入录制**；用户选择"仅录可用路"后才进入阶段 2。
 * 阶段 2（正式）：四路 start → 首段写入 → fsync 成功才置 RECORDING。
 *
 * 当前 [RecordingEnvironment.engineAvailable] 为 false 且未挂引擎时，即使摄像头就绪也不会假装开始录制；
 * 状态机停留在 ERROR 并带 `engine` 阶段错误，这是**如实状态**。挂上 [engine] 后进入真实阶段 2。
 */
class RecordingController(
    private val env: RecordingEnvironment,
    private val settingsProvider: () -> RecordSettings,
    private val worker: Executor,
    /** 探测留在 worker；结果与状态变更由宿主指定的线程串行提交，UI 宿主必须传主线程。 */
    private val callbackExecutor: Executor = Executor { it.run() },
) {
    /** 真实录制引擎（B3）。为 null 时 startWithDirections 保持"引擎未接入"如实报错。 */
    var engine: RecordingEnginePort? = null

    @Volatile
    var snapshot: RecordingSnapshot = RecordingSnapshot()
        private set

    var listener: ((RecordingSnapshot) -> Unit)? = null
    var preflightListener: ((PreflightOutcome) -> Unit)? = null

    /** 引擎回调 → 快照（经 callbackExecutor 串行提交；close 后不再交付）。 */
    private val engineListener = object : RecordingEngineListener {
        private fun post(block: () -> Unit) {
            callbackExecutor.execute { if (!closed) block() }
        }

        override fun onSegmentStarted(index: Int, segmentDir: java.io.File) {
            post { publish(snapshot.copy(segmentIndex = index)) }
        }

        override fun onRecordingActive() {
            post {
                publish(
                    snapshot.copy(
                        state = RecordingState.RECORDING,
                        startedAtMillis = System.currentTimeMillis(),
                        error = null,
                    ),
                )
            }
        }

        override fun onSegmentFinished(index: Int, outcome: com.galaxy.autorecord.storage.FinishOutcome) {
            post { android.util.Log.i("AutoRecordEngine", "段收尾 #$index ${outcome.state} complete=${outcome.isComplete}") }
        }

        override fun onProgress(bytesWritten: Long, segmentElapsedMs: Long, segmentIndex: Int) {
            post {
                publish(
                    snapshot.copy(
                        bytesWritten = bytesWritten,
                        segmentElapsedMillis = segmentElapsedMs,
                        segmentIndex = segmentIndex,
                    ),
                )
            }
        }

        override fun onStopped(error: String?) {
            post {
                if (error == null) {
                    publish(
                        RecordingSnapshot(
                            state = RecordingState.IDLE,
                            cameras = SegmentLayout.DIRECTIONS.associateWith { CameraState.STOPPED },
                        ),
                    )
                } else {
                    publish(
                        snapshot.copy(
                            state = RecordingState.ERROR,
                            error = RecordingError(
                                code = "engine_error",
                                source = "录制引擎",
                                stage = "engine",
                                detail = error,
                                recoverable = true,
                            ),
                        ),
                    )
                }
            }
        }
    }

    @Volatile private var preflightToken = 0
    @Volatile private var closed = false
    private var stopRequested = false

    /** FGS拥有真实会话；新建页面直接接续其快照，不新开编码器。 */
    fun syncFromSession(value:RecordingSnapshot) { if(!closed)publish(value) }

    fun requestStart() {
        if (closed) return
        val token = ++preflightToken
        stopRequested = false
        publish(snapshot.copy(state = RecordingState.STARTING, error = null))
        val settings = settingsProvider()
        worker.execute {
            val outcome = try {
                runPreflight(settings)
            } catch (error: Exception) {
                android.util.Log.w("AutoRecordPreflight", "录制预检异常", error)
                PreflightOutcome.Failed(listOf(PreflightFailure(null, "preflight", "预检失败：${error.javaClass.simpleName}")))
            }
            callbackExecutor.execute result@{
                if (closed) return@result
                if (token != preflightToken) {
                    // 新请求/停止已接管界面，旧结果不能再关弹层或覆盖新状态。
                    return@result
                }
                when (outcome) {
                    is PreflightOutcome.Failed -> {
                        publish(
                            snapshot.copy(
                                state = RecordingState.ERROR,
                                error = RecordingError(
                                    code = "preflight_failed",
                                    source = outcome.failures.firstOrNull()?.direction?.let { directionLabel(it) } ?: "系统",
                                    stage = outcome.failures.firstOrNull()?.stage ?: "preflight",
                                    detail = outcome.failures.joinToString("；") { f ->
                                        (f.direction?.let { directionLabel(it) + "：" } ?: "") + f.detail
                                    },
                                    recoverable = true,
                                ),
                            ),
                        )
                        preflightListener?.invoke(outcome)
                    }
                    is PreflightOutcome.Passed -> {
                        preflightListener?.invoke(outcome)
                        // 阶段 2：预检全过后自动开始真实录制（失败弹层的"仅录可用路"也走 startWithDirections）。
                        if (engine != null) startWithDirections(outcome.directions)
                    }
                    PreflightOutcome.Cancelled -> preflightListener?.invoke(PreflightOutcome.Cancelled)
                }
            }
        }
    }

    /** 销毁宿主使旧预检结果失效；worker 的所有权仍归创建它的宿主。 */
    fun close() {
        closed = true
        preflightToken++
        listener = null
        preflightListener = null
    }

    /** 用户在预检失败弹层里显式选择"仅录可用路"；预检全过时也会走到这里（阶段 2）。 */
    fun startWithDirections(directions: List<String>) {
        val engine = this.engine
        if (engine == null) {
            publish(
                snapshot.copy(
                    state = RecordingState.ERROR,
                    cameras = SegmentLayout.DIRECTIONS.associateWith {
                        if (it in directions) CameraState.WAITING_FIRST_FRAME else CameraState.DISCONNECTED
                    },
                    error = RecordingError(
                        code = "engine_not_ready",
                        source = "录制引擎",
                        stage = "engine",
                        detail = "录制引擎未接入（B3）：不会创建编码器、不会写盘，状态保持为异常",
                        recoverable = false,
                    ),
                ),
            )
            return
        }
        publish(
            snapshot.copy(
                state = RecordingState.STARTING,
                cameras = SegmentLayout.DIRECTIONS.associateWith {
                    if (it in directions) CameraState.WAITING_FIRST_FRAME else CameraState.DISCONNECTED
                },
                error = null,
            ),
        )
        engine.start(directions, settingsProvider(), engineListener)
    }

    fun requestStop() {
        preflightToken++
        stopRequested = true
        if (snapshot.state == RecordingState.IDLE) return
        val engine = this.engine
        if (engine != null && snapshot.state != RecordingState.ERROR) {
            publish(snapshot.copy(state = RecordingState.STOPPING))
            engine.requestStop() // 真实收尾（EOS→drain→muxer.stop→fsync→改名→清单）后 onStopped 回 IDLE
            return
        }
        publish(snapshot.copy(state = RecordingState.STOPPING))
        // 没有引擎就没有真实收尾，直接回到 IDLE 并清空累计量；不编造"已保存 N 段"。
        publish(
            RecordingSnapshot(
                state = RecordingState.IDLE,
                cameras = SegmentLayout.DIRECTIONS.associateWith { CameraState.STOPPED },
            ),
        )
    }

    /** 每秒由界面调用：只推进展示用的计时，不产生任何"正在录制"的错觉（状态没到 RECORDING 就不计时）。 */
    fun tick(nowMillis: Long) {
        // 真实引擎/FGS 已发布分段单调时钟；UI 不得再用墙钟取模覆盖它。
        if (engine != null) return
        if (snapshot.state != RecordingState.RECORDING) return
        val elapsed = nowMillis - snapshot.startedAtMillis
        pub(snapshot.copy(
            segmentElapsedMillis = elapsed % (settingsProvider().segmentSeconds * 1000L),
        ))
    }

    fun clearError() {
        if (snapshot.state == RecordingState.ERROR) {
            publish(
                RecordingSnapshot(
                    state = RecordingState.IDLE,
                    cameras = SegmentLayout.DIRECTIONS.associateWith { CameraState.STOPPED },
                ),
            )
        }
    }

    val isBusy: Boolean
        get() = snapshot.state == RecordingState.STARTING || snapshot.state == RecordingState.STOPPING

    private fun runPreflight(settings: RecordSettings): PreflightOutcome {
        val failures = mutableListOf<PreflightFailure>()
        val channels = env.channels()
        val live = channels.filterValues { it.isLive }.keys.toList()

        channels.filterValues { !it.isLive }.forEach { (direction, status) ->
            failures += PreflightFailure(direction, "channel", status.detail.ifEmpty { status.state.name })
        }
        if (channels.isEmpty()) {
            failures += PreflightFailure(null, "channel", "没有可用的摄像头通道")
        }
        env.checkCameraLayout()?.let { failures += it }
        env.checkEncoder(settings)?.let { failures += it }
        env.checkStorage(settings)?.let { failures += it }
        if (!env.engineAvailable) {
            failures += PreflightFailure(null, "engine", "录制引擎不可用（模拟器构建或未接入），本次不会开始写盘")
        }
        // 软编 + 3/4 路必须拒绝（技术口径 §2）：不静默降级、不假装四路可录。
        if (settings.encoderImplementation == RecordSettings.IMPL_SOFTWARE && live.size >= 3) {
            failures += PreflightFailure(null, "encoder", "四路软件编码未在本车验证（软编仅支持 1–2 路）")
        }

        return if (failures.isEmpty()) PreflightOutcome.Passed(live) else PreflightOutcome.Failed(failures)
    }

    private fun publish(next: RecordingSnapshot) {
        pub(next)
        listener?.invoke(next)
    }

    private fun pub(next: RecordingSnapshot) {
        snapshot = next
    }
}
