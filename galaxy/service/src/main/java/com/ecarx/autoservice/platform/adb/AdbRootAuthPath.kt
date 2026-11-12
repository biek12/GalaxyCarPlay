package com.ecarx.autoservice.platform.adb

/**
 * 选择已有证据的 VPWD 预检车型，并保留 Android 11 E245 例外。
 *
 * 不读设备、不持有连接。调用方在认证准备线程传入已经采集的事实。
 */
internal object AdbRootAuthPath {
    private val vpwdModels = listOf("P181-E04", "E245", "P171-A1")
    private const val E245_DEVICE = "antora1000_e245_plus"
    private const val E245_FINGERPRINT = "/antora1000_e245_plus_car/antora1000_e245_plus:"

    /**
     * 该事实是否走 VPWD 预检。
     * Android 11 且设备与指纹都命中 E245 时返回 false，其余命中型号返回 true。
     */
    fun usesVpwdPreflight(facts: AdbRootDeviceFacts): Boolean {
        if (facts.sdk == 30 &&
            facts.device?.equals(E245_DEVICE, true) == true &&
            facts.fingerprint?.contains(E245_FINGERPRINT, true) == true
        ) {
            return false
        }
        return facts.model?.let { model -> vpwdModels.any { model.contains(it, true) } } == true
    }

    /**
     * 校验重试次数。同一 E245/Android 11 例外为 15 次，其余为 5 次。
     */
    fun verificationAttempts(facts: AdbRootDeviceFacts): Int =
        if (facts.sdk == 30 &&
            facts.device?.equals(E245_DEVICE, true) == true &&
            facts.fingerprint?.contains(E245_FINGERPRINT, true) == true
        ) {
            15
        } else {
            5
        }
}
