package com.galaxy.autocluster.theme

import android.graphics.Color
import android.graphics.Typeface
import com.galaxy.auto.contract.*

/** 在加载/日夜/样式revision变更时解析；热路径不重复解析XML、颜色或数值字符串。 */
internal class VisualProperties(raw: Map<String, String>, palette: Map<String, String>, val typeface: Typeface, colorKeys: Set<String>) {
    private val raw = raw.toMap()
    private val numbers = raw.mapNotNull { (key, value) -> value.toFloatOrNull()?.let { key to it } }.toMap()
    private val colors = raw.filterKeys { it in colorKeys }.mapValues { (_, value) ->
        Color.parseColor(if (value.startsWith('@')) palette.getValue(value.drop(1)) else value)
    }
    fun string(key: String, default: String = ""): String = raw[key] ?: default
    fun number(key: String, default: Float = 0f): Float = numbers[key] ?: default
    fun optionalNumber(key: String): Float? = numbers[key]
    fun bool(key: String): Boolean = raw[key] == "true"
    fun color(key: String, default: Int = Color.WHITE): Int = colors[key] ?: default
    private val lists = listOf("numeralSkip", "extraMarks").associateWith { raw[it].orEmpty().split(',').mapNotNull { it.trim().toFloatOrNull() } }
    fun values(key: String): List<Float> = lists[key].orEmpty()
}
internal data class SceneRenderNode(val node: SceneNode, val properties: VisualProperties, val children: List<SceneRenderNode>)
internal class CompiledScene(val scene: ThemeScene, val night: Boolean, resources: ThemeResources, cache: ScenePropertyCache = ScenePropertyCache()) {
    private val useNight = night && !scene.document.shareDayNight
    val background = if (useNight) scene.document.nightBackground else scene.document.dayBackground
    val palette = if (useNight) scene.styles.nightPalette else scene.styles.dayPalette
    val backgroundColor = parseColor(background.color)
    val secondColor = parseColor(background.second)
    val nodes: List<SceneRenderNode>
    val visibleNodes: List<SceneRenderNode>
    val sources: Set<String>
    val map: SceneRect?
    val mapAppearance: MapAppearance
    val backdrops: List<MapBackdrop>
    val mapRadius: Float
    init {
        fun compile(node: SceneNode): SceneRenderNode {
            val spec = ComponentProperties.byType.getValue(node.type)
            val allowed = spec.properties.filter { it.styleable }.map { it.key }.toSet()
            val modeStyles = if (useNight) scene.styles.night else scene.styles.day
            val own = if (useNight) node.night else node.day
            val properties=cache.get(node.type,listOf(scene.styles.shared,modeStyles,node.properties,own,palette),useNight) {
                val raw = ComponentProperties.defaults(node.type) + scene.styles.shared.filterKeys { it in allowed } + modeStyles.filterKeys { it in allowed } + node.properties + own
                VisualProperties(raw, palette, resources.font(raw), spec.properties.filter { it.kind == PropertyKind.COLOR }.map { it.key }.toSet())
            }
            return SceneRenderNode(node,properties,node.children.map(::compile))
        }
        nodes = scene.document.nodes.map(::compile)
        val dependencies = mutableSetOf<String>(); var viewport: SceneRect? = null; var radius = 0f
        var appearance=MapAppearance();val regions=mutableListOf<MapBackdrop>();val visible=mutableListOf<SceneRenderNode>()
        fun collect(nodes: List<SceneRenderNode>, x: Float, y: Float) {
            for (item in nodes) {
                if (!item.node.visible) continue
                visible.add(item)
                val rect = item.node.rect
                val spec = ComponentProperties.byType.getValue(item.node.type)
                spec.properties.filter { it.kind == PropertyKind.SOURCE }.forEach {
                    item.properties.string(it.key).takeIf { it.isNotEmpty() }?.let(dependencies::add)
                }
                when (item.node.type) {
                    "map" -> {
                        viewport = rect.copy(x = rect.x + x, y = rect.y + y)
                        val p=item.properties;radius=if(p.bool("rounded"))p.number("cornerRadius")else 0f
                        appearance=MapAppearance(radius,if(p.bool("blurEnabled"))p.number("blurRadius")else 0f,p.string("blurStyle"),p.number("edgeWidth"),p.number("videoOpacity",1f))
                    }
                    "music-background","pages" -> {
                        val p=item.properties
                        if(p.string("backgroundStyle")=="map"){
                            val inset=minOf(p.number("backgroundInset"),rect.width/2-1,rect.height/2-1).coerceAtLeast(0f)
                            regions.add(MapBackdrop(MapViewport(x+rect.x+inset,y+rect.y+inset,rect.width-inset*2,rect.height-inset*2),if(p.bool("backgroundRounded"))p.number("backgroundRadius")else 0f,if(p.bool("backgroundBlur"))p.number("backgroundBlurRadius")else 0f))
                        }
                    }
                    "tires" -> listOf("frontLeft", "frontRight", "rearLeft", "rearRight").forEach { dependencies.add("vehicle.tire.$it") }
                    "pedals" -> dependencies.addAll(listOf("vehicle.accelerator", "vehicle.brake", "vehicle.steeringAngle"))
                    "indicators" -> item.properties.string("fields").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { dependencies.add("vehicle.$it") }
                    "status" -> item.properties.string("fields").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { dependencies.add("vehicle.$it") }
                }
                collect(item.children, x + rect.x, y + rect.y)
            }
        }
        collect(nodes, 0f, 0f); sources = dependencies; map = viewport; mapRadius = radius;mapAppearance=appearance;backdrops=regions.toList();visibleNodes=visible.toList()
    }
    private fun parseColor(value: String) = Color.parseColor(if (value.startsWith('@')) palette.getValue(value.drop(1)) else value)
}
