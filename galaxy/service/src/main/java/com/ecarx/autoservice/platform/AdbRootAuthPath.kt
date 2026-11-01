package com.ecarx.autoservice.platform

/** Build facts used by the EVCC DEX's Root branch selector; no guessed vehicle or network identity. */
internal data class AdbRootDeviceFacts(val model: String?, val device: String?, val fingerprint: String?, val sdk: Int)

/** Selects only the evidenced VPWD preflight models and preserves EVCC's Android 11 E245 exception. */
internal object AdbRootAuthPath {
    private val vpwdModels = listOf("P181-E04", "E245", "P171-A1")
    private const val E245_DEVICE = "antora1000_e245_plus"
    private const val E245_FINGERPRINT = "/antora1000_e245_plus_car/antora1000_e245_plus:"

    fun usesVpwdPreflight(facts: AdbRootDeviceFacts): Boolean {
        if (facts.sdk == 30 && facts.device?.equals(E245_DEVICE, true) == true &&
            facts.fingerprint?.contains(E245_FINGERPRINT, true) == true) return false
        return facts.model?.let { model -> vpwdModels.any { model.contains(it, true) } } == true
    }

    fun verificationAttempts(facts: AdbRootDeviceFacts): Int = if (
        facts.sdk == 30 && facts.device?.equals(E245_DEVICE, true) == true &&
        facts.fingerprint?.contains(E245_FINGERPRINT, true) == true
    ) 15 else 5
}
