package com.galaxy.autorecord.recording

/**
 * 引擎实测诊断（B5 P11）：录制引擎运行期的真实数据，供诊断页展示。
 * 只记录已实际发生的值；未录制/未实测时为 null，界面显示"—/未实测"，不写死文案。
 */
object EngineDiagnostics {

    /** 最近一段的真实收尾结果。 */
    data class LastSegment(
        val index: Int,
        val state: String,
        val encoderName: String,
        val implementation: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateBps: Long,
        val gopSeconds: Int,
        val frameCount: Long,
        val durationMs: Long,
        val directionsOk: List<String>,
        val directionsFailed: Map<String, String>,
        val finishedAtWallMs: Long,
    )

    /** 会话时钟锚点：墙钟毫秒 + 单调纳秒（段 PTS 与车辆样本同域基准）。 */
    data class ClockAnchor(val wallMillis: Long, val monoNanos: Long)

    @Volatile var lastSegment: LastSegment? = null
        internal set

    @Volatile var clockAnchor: ClockAnchor? = null
        internal set

    /** 最近一次引擎错误（成功开始新会话后清空）。 */
    @Volatile var lastError: String? = null
        internal set

    /** 最近一次循环覆盖动作（诊断展示"实际已删 N 段"）。 */
    @Volatile var lastRotationNote: String? = null
        internal set

    internal fun reset() {
        lastSegment = null
        lastError = null
        lastRotationNote = null
    }
}
