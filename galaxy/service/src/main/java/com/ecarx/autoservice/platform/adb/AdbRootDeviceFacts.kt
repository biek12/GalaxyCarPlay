package com.ecarx.autoservice.platform.adb

/**
 * Root 分支选择用的设备事实。不猜测车型或网络身份。
 *
 * 由调用方读完系统属性后传入。本类型不访问设备，也没有需要释放的资源。
 */
internal data class AdbRootDeviceFacts(
    val model: String?,
    val device: String?,
    val fingerprint: String?,
    val sdk: Int,
)
