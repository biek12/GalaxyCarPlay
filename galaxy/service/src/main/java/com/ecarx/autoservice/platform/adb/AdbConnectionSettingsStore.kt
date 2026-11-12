package com.ecarx.autoservice.platform.adb

/**
 * 本机 ADB 端点偏好的读写边界。实现方拥有偏好存储。
 */
internal interface AdbConnectionSettingsStore {
    /** 读取当前偏好。缺键时由实现返回既有默认值。 */
    fun read(): AdbConnectionSettings

    /** 保存偏好。不合规时返回 false，且不写入。 */
    fun save(settings: AdbConnectionSettings): Boolean
}
