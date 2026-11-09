package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot

/** G733 XSFNaviService AmapEventProcessor的编号表；Apple语义对照jiopia/DiPlay-ECARX dae07bfc。
 * L6 NaviServiceEntry.getETA返回routeRemainTime，单位秒，不能发送ECARX参考使用的epoch。
 */
internal object GalaxyL6NavigationValues {
    fun turnId(type: Int, drivingSide: Int): Int? {
        val amap = when (type) {
            0, 3 -> 9
            1, 20, 47 -> 2
            2, 21, 48 -> 3
            13, 49, 52, 22 -> 4
            14, 50, 53, 23 -> 5
            8, 9 -> if (drivingSide == 1) 4 else 5
            4, 18, 26 -> 8
            19 -> 24
            5, 15, 16, 17, 51 -> 20
            6 -> 11
            7 -> 12
            in 28..46 -> type + 93
            10, 12, 24, 25, 27 -> 15
            11 -> 1
            else -> return null
        }
        return when (amap) {
            in 1..20 -> amap + 100
            24 -> 143
            in 121..139 -> amap
            else -> null
        }
    }

    fun nonNegativeInt(value: Long?): Int? = value?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
    fun turnId(value: CarPlayNavigationSnapshot): Int? = value.maneuverType?.let {
        turnId(it, value.drivingSide ?: 0)
    }
}
