package com.galaxy.autorecord.core

/** 服务数值使用小数字符串（如 1.0）；未知/过期不能误报“关”。不推断其他枚举。 */
object TurnIndicatorState {
    fun display(left: VehicleValue, right: VehicleValue): String {
        val l = flag(left); val r = flag(right)
        return when {
            l == true && r == true -> "双闪"
            l == true -> "左"
            r == true -> "右"
            l == false && r == false -> "关"
            else -> "—"
        }
    }
    private fun flag(value: VehicleValue): Boolean? {
        if (value.status != VehicleValueStatus.LIVE) return null
        val text = value.value.trim().lowercase(java.util.Locale.ROOT)
        return when (text) {
            "true", "on" -> true
            "false", "off" -> false
            else -> when (text.toDoubleOrNull()) { 1.0 -> true; 0.0 -> false; else -> null }
        }
    }
}
