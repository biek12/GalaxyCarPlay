package com.galaxy.auto.contract

import android.os.Bundle

/** 仪表产品方案。模式、背景和元素分别保存，基础能力开关仍由AutoService管理。 */
data class ClusterDisplayConfiguration(
    val mode: String = OVERLAY,
    val background: String = ORIGINAL,
    val music: Boolean = true,
    val image: String = "",
) {
    init {
        require(mode in MODES) { "cluster_mode_invalid" }
        require(background in BACKGROUNDS) { "cluster_background_invalid" }
        // 图片只引用AutoCluster私有资源；不能把任意路径或URI交给Root执行。
        require(image.isEmpty() || IMAGE.matches(image)) { "cluster_image_invalid" }
        require(background != IMAGE_BACKGROUND || image.isNotEmpty()) { "cluster_image_missing" }
    }

    fun bundle() = Bundle().apply {
        putInt("schema", 1)
        putString("mode", mode)
        putString("background", background)
        putBoolean("music", music)
        putString("image", image)
    }

    val needsElements get() = mode == OEM_ELEMENTS && (music || background != ORIGINAL)

    companion object {
        const val OVERLAY = "overlay"
        const val OEM_ELEMENTS = "oem-elements"
        const val ORIGINAL = "original"
        const val IMAGE_BACKGROUND = "image"
        const val CARPLAY_MAP = "carplay-map"
        const val LOCAL_MAP = "local-map"
        val MODES = listOf(OVERLAY, OEM_ELEMENTS)
        val BACKGROUNDS = listOf(ORIGINAL, IMAGE_BACKGROUND, CARPLAY_MAP, LOCAL_MAP)
        private val IMAGE = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,95}\\.(png|jpg|jpeg|webp)")

        fun from(value: Bundle): ClusterDisplayConfiguration {
            require(value.getInt("schema") == 1) { "cluster_schema_unsupported" }
            return ClusterDisplayConfiguration(
                mode = requireNotNull(value.getString("mode")),
                background = requireNotNull(value.getString("background")),
                music = value.getBoolean("music", true),
                image = value.getString("image").orEmpty(),
            )
        }
    }
}
