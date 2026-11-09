package com.galaxy.autorecord.core

/**
 * 录制状态与错误码。界面文案与诊断必须使用这里的分类，不允许各页自造状态词
 * （docs/AutoRecord页面功能与设置规格-20261103.md §2.5）。
 */
enum class RecordingState {
    /** 未录制：用户未开启，或已被用户停止。 */
    IDLE,

    /** 已请求开始，尚未取得首帧/未完成编码器创建。 */
    STARTING,

    /** 真实出帧并写盘。 */
    RECORDING,

    /** 正在收尾当前片段（EOS→drain→muxer.stop→fsync→改名→索引）。 */
    STOPPING,

    /** 有错误暂停；是否可恢复由 [RecordingError] 决定，不静默重试。 */
    ERROR,
}

/** 摄像头单路状态。某路异常必须能定位到方向，不能整块报"摄像头错误"。 */
enum class CameraState {
    /** 功能被用户在 AutoService 关闭。 */
    DISABLED,

    /** 权限/身份不足。 */
    NO_PERMISSION,

    /** 原厂环视或倒车占用中，等待释放。 */
    WAITING_VENDOR_RELEASE,

    /** 已申请会话，尚未拿到首帧。 */
    WAITING_FIRST_FRAME,

    ACTIVE,

    /** 断流，正在恢复或已放弃（由 generation 区分）。 */
    DISCONNECTED,

    /** 用户/系统主动停止。 */
    STOPPED,
}

/** 存储显式状态，避免"写不进去"被笼统报成失败。 */
enum class StorageState {
    OK,

    /** 剩余低于最低剩余空间，暂停录制。 */
    LOW_SPACE,

    /** 配额满且不可覆盖。 */
    FULL,

    /** 只读卷。 */
    READ_ONLY,

    /** 卷已移除（U 盘拔出）；必须报卷名，且不得自动换介质。 */
    REMOVED,

    /** 写入错误（含 errno/异常类型与卷名）。 */
    WRITE_ERROR,

    /** 该段写入失败，保留为 partial。 */
    IO_ERROR,

    /** 实测吞吐低于需求值。 */
    THROTTLED,
}

/**
 * 录制错误：必须带来源与阶段，供界面与诊断直接展示。
 *
 * @param source 前/后/左/右、车辆数据、介质卷名或编码器名
 * @param stage 发生阶段（create/configure/first-frame/write/close/index）
 */
data class RecordingError(
    val code: String,
    val source: String,
    val stage: String,
    val detail: String,
    val recoverable: Boolean = false,
)

/** 全局录制快照，供所有页面共用（录制与页面解耦）。 */
data class RecordingSnapshot(
    val state: RecordingState = RecordingState.IDLE,
    val startedAtMillis: Long = 0L,
    val segmentIndex: Int = 0,
    val segmentElapsedMillis: Long = 0L,
    val cameras: Map<String, CameraState> = SegmentLayout.DIRECTIONS.associateWith { CameraState.STOPPED },
    val storage: StorageState = StorageState.OK,
    val bytesWritten: Long = 0L,
    val error: RecordingError? = null,
) {
    val isRecording: Boolean get() = state == RecordingState.RECORDING || state == RecordingState.STARTING

    /** 缺路方向：非 ACTIVE 即视为缺路，界面必须标区间而不是借用其他方向。 */
    val missingDirections: List<String>
        get() = cameras.filterValues { it != CameraState.ACTIVE }.keys.toList()
}