package com.shilapi.xcertplay

/** L6原厂EAS与在线源6已核验；保留L7旧通道兼容，不借用其他应用身份。 */
internal object L7MediaSourcePolicy {
    fun resolve(easSupport: Int?, info: Class<*>,
                channel: GalaxyMediaReportingChannel.Mode = GalaxyMediaReportingChannel.Mode.CURRENT,
                vehicle: String? = null): Int {
        // L6契约来自目标原厂源码；包可见性探测失败不能把已确定车型误切成旧源13。
        if (vehicle != "l6" && channel == GalaxyMediaReportingChannel.Mode.CURRENT && easSupport != 1)
            return L7MediaCenterPort.CARPLAY_SOURCE
        val local = channel == GalaxyMediaReportingChannel.Mode.LOCAL
        val value = info.getField(if (local) "SOURCE_TYPE_LOCAL" else "SOURCE_TYPE_ONLINE").getInt(null)
        check(value == if (local) 0 else 6) { "SDK_SOURCE_CONTRACT_MISMATCH" }
        return value
    }
}
