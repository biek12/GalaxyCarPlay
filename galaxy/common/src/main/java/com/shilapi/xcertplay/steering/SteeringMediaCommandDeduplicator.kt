package com.shilapi.xcertplay.steering

/**
 * 跨源媒体命令去重：同一动作在 250ms 窗口内来自不同入口（方控 HardKey 与原车媒体中心回调）
 * 时只放行第一个，抑制重复转发；同一入口的连按与不同动作保留，不影响正常快速操作。
 *
 * 迁自星枢方控/媒体汇流的来源去重行为（项目规划 §3.6“跨源250ms去重”）。时间戳由调用方传入，
 * 使回归测试可确定化，不依赖 SystemClock；只在放行时更新记录，被抑制的命令不刷新窗口基准。
 */
internal class SteeringMediaCommandDeduplicator(private val windowMs: Long = 250) {
    private data class Last(val source: String, val atMs: Long)

    private val lastByAction = mutableMapOf<SteeringAction, Last>()

    /** 只查询窗口是否允许该动作，不写入记录；调用方在命令真的发出后再调用 [accept]。 */
    @Synchronized
    fun allowed(action: SteeringAction, source: String, nowMs: Long): Boolean {
        val last = lastByAction[action] ?: return true
        return !(last.source != source && nowMs - last.atMs in 0 until windowMs)
    }

    @Synchronized
    fun accept(action: SteeringAction, source: String, nowMs: Long): Boolean {
        val last = lastByAction[action]
        // 仅当动作相同、来源不同且落在窗口内才抑制；同源连按与跨源超时都放行。
        if (last != null && last.source != source && nowMs - last.atMs in 0 until windowMs) {
            return false
        }
        lastByAction[action] = Last(source, nowMs)
        return true
    }

    /** 仅供回归测试清理窗口，产品路径不调用。 */
    @Synchronized
    fun clear() {
        lastByAction.clear()
    }
}
