package com.galaxy.autocluster.theme.render

import com.galaxy.autocluster.theme.*

/** 输入单位/方向与显示约定分开。没有kW单位或有效来源时保持未知，禁止从raw功率猜符号。 */
internal data class PowerFlow(val drive: Float?, val regeneration: Float?) {
    companion object {
        fun read(p: VisualProperties, data: SceneData, now: Long): PowerFlow {
            if (p.string("powerInput") == "signed") {
                val value = data.kilowatts(p.string("signedPowerSource"), now) ?: return PowerFlow(null, null)
                val canonical = if (p.string("powerPositive") == "regen") -value else value
                return PowerFlow(canonical.coerceAtLeast(0f), (-canonical).coerceAtLeast(0f))
            }
            return PowerFlow(data.kilowatts(p.string("drivePowerSource"), now)?.takeIf { it >= 0 },
                data.kilowatts(p.string("regenPowerSource"), now)?.takeIf { it >= 0 })
        }
    }
}
