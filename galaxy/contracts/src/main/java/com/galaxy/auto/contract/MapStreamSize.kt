package com.galaxy.auto.contract

import kotlin.math.ceil

/** 111视频请求尺寸与组件矩形分开；按编码块向上对齐，位置移动不触发重新协商。 */
data class MapStreamSize(val width: Int, val height: Int) {
    init { require(width in 8..AutoContract.WIDTH && height in 8..AutoContract.MAP_HEIGHT && width % 8 == 0 && height % 8 == 0) }
    fun bundle() = android.os.Bundle().apply { putInt("width", width); putInt("height", height) }
    companion object {
        val DEFAULT = MapStreamSize(AutoContract.WIDTH, AutoContract.MAP_HEIGHT)
        fun forViewport(value: MapViewport?) = value?.let {
            MapStreamSize((ceil(it.width / 8).toInt() * 8).coerceIn(8, AutoContract.WIDTH),
                (ceil(it.height / 8).toInt() * 8).coerceIn(8, AutoContract.MAP_HEIGHT))
        } ?: DEFAULT
        fun from(value: android.os.Bundle) = MapStreamSize(value.getInt("width"), value.getInt("height"))
    }
}
