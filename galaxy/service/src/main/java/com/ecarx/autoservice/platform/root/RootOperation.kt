package com.ecarx.autoservice.platform.root

/**
 * Root 帧的 operation 合同。
 *
 * 数字、是否带 JSON、是否复用车辆连接只定义在这里。
 * 业务代码调用 [RootServiceClient] 的具名方法，不读取这些数字。
 */
internal enum class RootOperation(
    val code: Int,
    /** 请求体是否为定长 JSON。op1 由车辆读取端口写入；op4 由归档拒绝器消费。 */
    val carriesJson: Boolean,
    /** 是否复用车辆长连接。只有车辆读取为 true。 */
    val reusesVehicleConnection: Boolean,
) {
    HEALTHY(0, false, false),
    READ_VEHICLE(1, true, true),
    STOP_HELPER(2, false, false),
    SHELL(3, true, false),
    ARCHIVED_DEPLOY(4, true, false),
    RECEIVER_CLEANUP(5, true, false),
    ARCHIVED_BRIDGE(6, false, false),
    AUTHORIZE_ADB_KEY(7, true, false),
    STOP_VEHICLE(8, false, false),
    SENTINEL(9, true, false),
    SENTINEL_STREAM(10, false, false),
    HOOK_CONTROL(11, true, false),
    HOOK_RECEIPT(12, false, false),
    ;

    /**
     * 通用请求是否在 operation 整数后写定长 JSON。
     * 车辆读取虽带 JSON，但不走这条通用请求。结果与原来的 `3..5、7、9、11` 相同。
     */
    val requestWritesJson: Boolean
        get() = carriesJson && !reusesVehicleConnection

    /**
     * helper 正常分发是否先读定长 JSON。
     * op4 虽带 JSON，但在进入分发前已由归档拒绝器读完。
     */
    val dispatchReadsJson: Boolean
        get() = carriesJson && this != ARCHIVED_DEPLOY

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** 未知数字返回 null，调用方回 `unknown_operation`。 */
        fun fromCode(code: Int): RootOperation? = byCode[code]
    }
}
