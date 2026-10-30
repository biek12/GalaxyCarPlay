package com.ecarx.autoservice.projection

import com.galaxy.auto.contract.AutoContract

/** 输出profile来自已验证L6 HAB；Android输入Surface与QNX屏号分开。 */
internal object GalaxyHabLayout {
    const val WIDTH = AutoContract.WIDTH
    const val HEIGHT = AutoContract.HEIGHT
    const val MAP_WIDTH = AutoContract.WIDTH
    const val MAP_HEIGHT = AutoContract.MAP_HEIGHT
    const val FRAME_INTERVAL_MILLIS = 100L
}
