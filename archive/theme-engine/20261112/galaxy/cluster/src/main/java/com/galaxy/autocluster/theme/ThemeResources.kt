package com.galaxy.autocluster.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import java.io.Closeable
import java.io.File

/** 资源在IO线程准备；仅加载场景引用的文件，绘制时不打开文件/解码图片。
 * close只撤销自有引用；不手动recycle仍可能被RenderThread引用的Bitmap。
 */
internal class ThemeResources(scene: ThemeScene, cacheDir: File, readAsset: (String)->ByteArray) : Closeable {
    constructor(theme: ThemePackage,scene: ThemeScene,cacheDir: File):this(scene,cacheDir,theme::content)
    private val images = mutableMapOf<String, Bitmap>()
    private val fontFiles = mutableMapOf<String, Typeface>()
    private val fonts = mutableMapOf<String, Typeface>()
    init {
        val assets = SceneTree.flatten(scene.document.nodes).flatMap { node ->
            listOf(node.properties, node.day, node.night).flatMap { props -> listOfNotNull(props["image"], props["fontAsset"]) }
        }.toMutableSet()
        listOf(scene.styles.shared, scene.styles.day, scene.styles.night).forEach { assets.add(it["fontAsset"].orEmpty()); assets.add(it["image"].orEmpty()) }
        assets.add(scene.document.dayBackground.image); assets.add(scene.document.nightBackground.image)
        var pixels = 0L
        for (name in assets.filter { it.isNotEmpty() }) {
            val bytes = readAsset(name)
            if (name.endsWith(".ttf") || name.endsWith(".otf")) {
                val file = File.createTempFile("cluster-font-", ".tmp", cacheDir)
                try { file.writeBytes(bytes); fontFiles[name] = Typeface.createFromFile(file) }
                finally { file.delete() }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "theme_image_decode_failed" }
                var sample = 1
                while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 1920L * 1080L) sample *= 2
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })) { "theme_image_decode_failed" }
                pixels += bitmap.width.toLong() * bitmap.height
                require(pixels <= 8_000_000) { "theme_decoded_image_budget" }
                images[name] = bitmap
            }
        }
        // 两种模式预热字体；CompiledScene在owner线程可只取缓存。
        for (node in SceneTree.flatten(scene.document.nodes)) {
            val defaults = ComponentProperties.defaults(node.type)
            for (values in listOf(scene.styles.day + node.day, scene.styles.night + node.night))
                font(defaults + scene.styles.shared + node.properties + values)
        }
    }
    fun image(name: String): Bitmap? = images[name]
    fun font(raw: Map<String, String>): Typeface {
        val name = raw["font"] ?: "sans"
        val weight = raw["fontWeight"]?.toFloatOrNull()?.toInt()?.coerceIn(100, 900) ?: 400
        val asset = raw["fontAsset"].orEmpty()
        return fonts.getOrPut("$name:$weight:$asset") {
            val base = when (name) {
                "asset" -> requireNotNull(fontFiles[asset]) { "theme_font_missing" }
                "mono" -> Typeface.MONOSPACE
                "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                "rounded" -> Typeface.create("sans-serif-rounded", Typeface.NORMAL)
                else -> Typeface.SANS_SERIF
            }
            Typeface.create(base, weight, false)
        }
    }
    override fun close() { images.clear(); fonts.clear(); fontFiles.clear() }
}
