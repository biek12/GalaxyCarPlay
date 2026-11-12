package com.galaxy.autocluster.theme

internal data class SceneRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(x: Float, y: Float) = x >= this.x && y >= this.y && x <= this.x + width && y <= this.y + height
}
internal data class SceneNode(
    val id: String, val type: String, val name: String, val rect: SceneRect,
    val properties: Map<String, String> = emptyMap(),
    val day: Map<String, String> = emptyMap(), val night: Map<String, String> = emptyMap(),
    val visible: Boolean = true, val locked: Boolean = false, val children: List<SceneNode> = emptyList(),
)
internal data class SceneBackground(val kind: String = "solid", val color: String = "#07100D",
    val second: String = "#142B27", val image: String = "", val angle: Float = 90f)
internal data class SceneDocument(
    val nodes: List<SceneNode>, val appearance: String = "auto", val shareDayNight: Boolean = false,
    val dayBackground: SceneBackground = SceneBackground(color = "#DCE5E7", second = "#BCCFD3"),
    val nightBackground: SceneBackground = SceneBackground(),
)
internal data class SceneStyles(
    val dayPalette: Map<String, String> = DAY, val nightPalette: Map<String, String> = NIGHT,
    val shared: Map<String, String> = emptyMap(), val day: Map<String, String> = emptyMap(), val night: Map<String, String> = emptyMap(),
) {
    companion object {
        val DAY = mapOf("foreground" to "#172D3B", "muted" to "#596C78", "accent" to "#1368FB", "panel" to "#EBF1F3", "track" to "#BBC9D0", "warning" to "#B36A16", "critical" to "#D23C42")
        val NIGHT = mapOf("foreground" to "#F2ECDD", "muted" to "#BDB6A3", "accent" to "#D9CDB0", "panel" to "#101B19", "track" to "#354740", "warning" to "#EDAF4C", "critical" to "#F25E62")
    }
}
internal data class ThemeScene(val document: SceneDocument, val styles: SceneStyles)

/** 所有几何操作同时作用于结构化场景；预览和最终输出读取同一份结果。 */
internal object SceneTree {
    fun flatten(nodes: List<SceneNode>): List<SceneNode> = nodes.flatMap { listOf(it) + flatten(it.children) }
    fun find(nodes: List<SceneNode>, id: String): SceneNode? {
        for (node in nodes) { if (node.id == id) return node; find(node.children, id)?.let { return it } }
        return null
    }
    fun update(nodes: List<SceneNode>, id: String, change: (SceneNode) -> SceneNode): List<SceneNode> = nodes.map {
        if (it.id == id) change(it) else it.copy(children = update(it.children, id, change))
    }
    fun remove(nodes: List<SceneNode>, ids: Set<String>): List<SceneNode> = nodes.filter { it.id !in ids }.map { it.copy(children = remove(it.children, ids)) }
    fun worldRects(nodes: List<SceneNode>, x: Float = 0f, y: Float = 0f): Map<String, SceneRect> = buildMap {
        for (node in nodes) {
            val r = node.rect.copy(x = x + node.rect.x, y = y + node.rect.y)
            put(node.id, r); putAll(worldRects(node.children, r.x, r.y))
        }
    }
    fun resize(node: SceneNode, rect: SceneRect): SceneNode {
        val sx = rect.width / node.rect.width; val sy = rect.height / node.rect.height
        return node.copy(rect = rect, children = node.children.map {
            resize(it, SceneRect(it.rect.x * sx, it.rect.y * sy, it.rect.width * sx, it.rect.height * sy))
        })
    }
    fun constrain(rect: SceneRect, width: Float = 1920f, height: Float = 532f): SceneRect {
        val w = rect.width.coerceIn(16f, width); val h = rect.height.coerceIn(16f, height)
        return SceneRect(rect.x.coerceIn(0f, width - w), rect.y.coerceIn(0f, height - h), w, h)
    }
}
