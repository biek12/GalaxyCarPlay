package com.ecarx.autoservice.projection

/** 一次会话最多两次恢复；握手成功不清零，持续提交100帧才允许新的恢复预算。 */
internal class GalaxyHabReconnect {
    private var failures = 0
    fun failed(): Long? = when (++failures) {
        1 -> 1000L
        2 -> 3000L
        else -> null
    }
    fun stableFrames() { failures = 0 }
}
