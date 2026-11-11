package com.galaxy.auto.contract

import android.os.Bundle

data class ProjectionPolicy(val mode: String = "content-aware", val maximumFps: Int = 30, val animationFps: Int = 30) {
    init { require(mode in setOf("fixed-cap", "content-aware") && maximumFps in 1..60 && animationFps in 1..maximumFps) { "projection_policy_invalid" } }
    fun bundle() = Bundle().apply { putInt("schema", 1); putString("mode", mode); putInt("maximumFps", maximumFps); putInt("animationFps", animationFps) }
    companion object {
        fun from(value: Bundle): ProjectionPolicy {
            require(value.getInt("schema") == 1)
            return ProjectionPolicy(value.getString("mode") ?: "", value.getInt("maximumFps"), value.getInt("animationFps"))
        }
    }
}

data class MapViewport(val x: Float, val y: Float, val width: Float, val height: Float) {
    init {
        require(listOf(x,y,width,height).all { it.isFinite() } && x >= 0 && y >= 0 && width > 0 && height > 0 &&
            x + width <= AutoContract.WIDTH + .01f && y + height <= AutoContract.HEIGHT + .01f) { "map_viewport_invalid" }
    }
    /** 始终完整等比适配；不裁掉地图源中的车道/限速/到达信息。 */
    fun fitted(sourceWidth: Int = AutoContract.WIDTH, sourceHeight: Int = AutoContract.MAP_HEIGHT): MapViewport {
        require(sourceWidth > 0 && sourceHeight > 0)
        val scale = minOf(width / sourceWidth, height / sourceHeight)
        val w = sourceWidth * scale; val h = sourceHeight * scale
        return MapViewport(x+(width-w)/2,y+(height-h)/2,w,h)
    }
}

/** token把场景配置与新主题Surface绑定；第一帧到达后才替换旧主题/几何。 */
data class SceneConfiguration(val token: Long, val themeId: String, val map: MapViewport?,val appearance: MapAppearance=MapAppearance(),val backdrops: List<MapBackdrop> = emptyList(),val lyrics: Boolean=false) {
    init { require(backdrops.size<=8);require(token > 0 && themeId.matches(Regex("[a-zA-Z][a-zA-Z0-9-]{0,79}"))) { "scene_configuration_invalid" } }
    fun bundle() = Bundle().apply {
        putBundle("appearance",appearance.bundle());putParcelableArrayList("backdrops",ArrayList(backdrops.map { it.bundle() }))
        putBoolean("lyrics",lyrics);putInt("schema",1); putLong("token",token); putString("themeId",themeId)
        putFloatArray("map",map?.let { floatArrayOf(it.x,it.y,it.width,it.height) } ?: floatArrayOf())
    }
    companion object {
        fun from(value: Bundle): SceneConfiguration {
            require(value.getInt("schema") == 1)
            val map = requireNotNull(value.getFloatArray("map"))
            require(map.size == 0 || map.size == 4)
            return SceneConfiguration(value.getLong("token"),value.getString("themeId") ?: "",if(map.isEmpty())null else MapViewport(map[0],map[1],map[2],map[3]),
                value.getBundle("appearance")?.let(MapAppearance::from)?:MapAppearance(),
                value.getParcelableArrayList<Bundle>("backdrops").orEmpty().map(MapBackdrop::from),value.getBoolean("lyrics"))
        }
    }
}

/** 参数为设计像素，模糊在自有GLES执行，兼容API30；不涉及系统窗口模糊。 */
data class MapAppearance(val cornerRadius: Float=0f,val blurRadius: Float=0f,
    val blurStyle: String="edges",val edgeWidth: Float=80f,val opacity: Float=1f) {
    init {
        require(listOf(cornerRadius,blurRadius,edgeWidth,opacity).all { it.isFinite() })
        require(cornerRadius in 0f..200f&&blurRadius in 0f..32f&&edgeWidth in 0f..400f&&opacity in 0f..1f)
        require(blurStyle in setOf("edges","uniform"))
    }
    fun bundle()=Bundle().apply { putFloat("radius",cornerRadius);putFloat("blur",blurRadius);putString("style",blurStyle);putFloat("edge",edgeWidth);putFloat("opacity",opacity) }
    companion object { fun from(value: Bundle)=MapAppearance(value.getFloat("radius"),value.getFloat("blur"),value.getString("style")?:"edges",value.getFloat("edge",80f),value.getFloat("opacity",1f)) }
}
data class MapBackdrop(val rect: MapViewport,val radius: Float=0f,val blurRadius: Float=12f) {
    init { require(radius.isFinite()&&blurRadius.isFinite()&&radius in 0f..200f&&blurRadius in 0f..32f) }
    fun bundle()=Bundle().apply { putFloatArray("rect",floatArrayOf(rect.x,rect.y,rect.width,rect.height));putFloat("radius",radius);putFloat("blur",blurRadius) }
    companion object { fun from(value: Bundle): MapBackdrop {val r=requireNotNull(value.getFloatArray("rect"));require(r.size==4);return MapBackdrop(MapViewport(r[0],r[1],r[2],r[3]),value.getFloat("radius"),value.getFloat("blur"))} }
}
