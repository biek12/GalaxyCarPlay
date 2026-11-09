package com.galaxy.autorecord.recording

import com.galaxy.autorecord.core.EventKind
import com.galaxy.autorecord.core.EventMark
import com.galaxy.autorecord.core.RecordSettings

/**
 * 事件标记判定（阈值来自 RecordSettings；只打"事件标记"，不做碰撞检测/事故认定）。
 *
 * 规则（页面规格 §7/§9）：
 * - 刹车/油门：阈值 + 滞回（enter/exit）+ 去抖（debounceMs）+ 最小事件时长（minEventMs）+ 合并间隔（mergeMs）；
 * - 横向辅助/巡航：状态跳变打离散事件（启用/退出），跳变需持续 debounceMs 才确认（回溯到跳变时刻）；
 * - 规则修改只影响新片段，历史片段按记录的规则版本解释、不回算。
 *
 * 时间均为相对片段起点的毫秒偏移（与 vehicles.jsonl 的 t、回放 positionMs 同域）。
 * 样本缺失（null）不触发状态变化、不补零、不臆造事件。
 */
class EventDetector(val rules: Rules) {

    data class Rules(
        val brakeEnter: Int,
        val brakeExit: Int,
        val accelEnter: Int,
        val accelExit: Int,
        val debounceMs: Int,
        val minEventMs: Int,
        val mergeMs: Int,
        val ruleVersion: String,
    ) {
        companion object {
            fun from(settings: RecordSettings): Rules = Rules(
                brakeEnter = settings.brakeEnter,
                brakeExit = settings.brakeExit,
                accelEnter = settings.accelEnter,
                accelExit = settings.accelExit,
                debounceMs = settings.debounceMs,
                minEventMs = settings.minEventMs,
                mergeMs = settings.mergeMs,
                ruleVersion = settings.eventRulesVersion,
            )
        }
    }

    private val events = mutableListOf<EventMark>()
    private val brake = ThresholdChannel(EventKind.BRAKE, rules.brakeEnter, rules.brakeExit)
    private val accel = ThresholdChannel(EventKind.ACCEL, rules.accelEnter, rules.accelExit)
    private val adas = DiscreteChannel(EventKind.ADAS_ON, EventKind.ADAS_OFF)
    private val cruise = DiscreteChannel(EventKind.CRUISE_ON, EventKind.CRUISE_OFF)
    private var lastT = 0L

    /** 消费一个车辆样本；[brakePct]/[accelPct] 为百分数数值（解析失败传 null）。 */
    fun onSample(
        tMs: Long,
        brakePct: Float?,
        accelPct: Float?,
        adasActive: Boolean?,
        cruiseActive: Boolean?,
    ) {
        if (tMs < lastT) return // 时间必须单调；迟到/回拨样本不参与判定
        lastT = tMs
        brake.onValue(tMs, brakePct)
        accel.onValue(tMs, accelPct)
        adas.onState(tMs, adasActive)
        cruise.onState(tMs, cruiseActive)
    }

    /** 收尾：关闭仍在持续的区间事件，过滤最小事件时长并合并邻近事件。 */
    fun finish(lastSampleMs: Long): List<EventMark> {
        brake.close(lastSampleMs)
        accel.close(lastSampleMs)
        adas.close()
        cruise.close()
        val merged = (events + brake.emitted + accel.emitted + adas.emitted + cruise.emitted)
            .filter { it.isDiscrete || it.durationMs >= rules.minEventMs }
            .sortedBy { it.startMs }
        return merge(merged)
    }

    private fun merge(sorted: List<EventMark>): List<EventMark> {
        val out = mutableListOf<EventMark>()
        for (event in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.kind == event.kind && !last.isDiscrete && !event.isDiscrete &&
                event.startMs - last.endMs <= rules.mergeMs
            ) {
                out[out.size - 1] = last.copy(
                    endMs = event.endMs,
                    peak = maxOfNullable(last.peak, event.peak),
                )
            } else {
                out += event
            }
        }
        return out
    }

    private fun maxOfNullable(a: Float?, b: Float?): Float? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    /** 阈值型区间通道（刹车/油门）：滞回 + 去抖 + 峰值。 */
    private inner class ThresholdChannel(
        private val kind: EventKind,
        private val enter: Int,
        private val exit: Int,
    ) {
        val emitted = mutableListOf<EventMark>()
        private var active = false
        private var pendingSince = -1L
        private var pendingEnter = false
        private var startMs = 0L
        private var peak: Float? = null

        fun onValue(tMs: Long, value: Float?) {
            if (value == null) return // 缺失不补零、不改状态
            if (!active) {
                if (value >= enter) {
                    if (pendingSince < 0 || !pendingEnter) {
                        pendingSince = tMs
                        pendingEnter = true
                    } else if (tMs - pendingSince >= rules.debounceMs) {
                        active = true
                        startMs = pendingSince // 回溯到首次越过阈值的时刻
                        peak = value
                        pendingSince = -1L
                    }
                } else {
                    pendingSince = -1L
                    pendingEnter = false
                }
            } else {
                peak = maxOfNullable(peak, value)
                if (value <= exit) {
                    if (pendingSince < 0 || pendingEnter) {
                        pendingSince = tMs
                        pendingEnter = false
                    } else if (tMs - pendingSince >= rules.debounceMs) {
                        active = false
                        emit(startMs, pendingSince)
                        pendingSince = -1L
                    }
                } else {
                    pendingSince = -1L
                }
            }
        }

        fun close(lastSampleMs: Long) {
            if (active) {
                emit(startMs, lastSampleMs.coerceAtLeast(startMs))
                active = false
            }
            pendingSince = -1L
        }

        private fun emit(start: Long, end: Long) {
            emitted += EventMark(
                kind = kind,
                startMs = start,
                endMs = end,
                peak = peak,
                ruleVersion = rules.ruleVersion,
            )
            peak = null
        }
    }

    /** 离散跳变通道（横向辅助/巡航）：启用与退出各记一条，跳变需持续 debounceMs。 */
    private inner class DiscreteChannel(
        private val onKind: EventKind,
        private val offKind: EventKind,
    ) {
        val emitted = mutableListOf<EventMark>()
        private var state: Boolean? = null
        private var pendingState: Boolean? = null
        private var pendingSince = -1L

        fun onState(tMs: Long, value: Boolean?) {
            if (value == null) return
            if (state == null) {
                state = value // 首个样本定初值，不产生事件
                return
            }
            if (value == state) {
                pendingState = null
                pendingSince = -1L
                return
            }
            if (pendingState != value) {
                pendingState = value
                pendingSince = tMs
            } else if (tMs - pendingSince >= rules.debounceMs) {
                val kind = if (value) onKind else offKind
                emitted += EventMark(
                    kind = kind,
                    startMs = pendingSince,
                    endMs = pendingSince,
                    peak = null,
                    ruleVersion = rules.ruleVersion,
                )
                state = value
                pendingState = null
                pendingSince = -1L
            }
        }

        fun close() = Unit // 离散事件不需要收尾补记；会话结束不臆造退出事件
    }

    companion object {
        /** 解析百分数数值；"42"、"42%"、" 42.5 " 可解析，其余返回 null（不臆造 0）。 */
        fun parsePercent(raw: String?): Float? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw.trim().removeSuffix("%").trim()
            return cleaned.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f }
        }

        /** 解析布尔状态（on/off/1/0/true/false…）；无法判定返回 null（不误报）。 */
        fun parseActive(raw: String?): Boolean? {
            if (raw.isNullOrBlank()) return null
            return when (raw.trim().lowercase()) {
                "1", "on", "true", "active", "enabled", "open", "yes" -> true
                "0", "off", "standby", "false", "inactive", "disabled", "close", "closed", "no", "none" -> false
                else -> {
                    val number = raw.trim().toFloatOrNull()
                    when {
                        number == null -> null
                        number > 0.5f -> true
                        number >= 0f -> false
                        else -> null
                    }
                }
            }
        }
    }
}
