package com.galaxy.autocluster.theme

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.ByteArrayInputStream
import java.io.StringWriter

/** XML只在加载/保存时解析；节点次序就是自有场景层序，不携带QNX窗口操作。 */
internal object SceneXml {
    fun read(theme: ThemePackage): ThemeScene {
        val scene = parser(theme.content("scene.xml"), "scene")
        val root = attributes(scene, setOf("schemaVersion", "appearance", "shareDayNight"))
        require(root["schemaVersion"] == "1") { "scene_version_unsupported" }
        var dayBackground = SceneDocument(emptyList()).dayBackground
        var nightBackground = SceneBackground()
        val nodes = mutableListOf<SceneNode>()
        val backgrounds = mutableSetOf<String>()
        children(scene) {
            when (scene.name) {
                "node" -> nodes.add(node(scene))
                "background" -> {
                    val a = attributes(scene, setOf("mode", "kind", "color", "second", "image", "angle"))
                    val mode = a.getValue("mode")
                    require(mode in setOf("day", "night") && backgrounds.add(mode)) { "scene_background_duplicate" }
                    val b = SceneBackground(a.getValue("kind"), a.getValue("color"), a.getValue("second"), a["image"].orEmpty(), a["angle"]?.toFloat() ?: 90f)
                    if (mode == "day") dayBackground = b else nightBackground = b
                    empty(scene)
                }
                else -> error("scene_element_unknown")
            }
        }
        endDocument(scene)
        val styles = parser(theme.content("styles.xml"), "styles")
        require(attributes(styles, setOf("schemaVersion"))["schemaVersion"] == "1") { "styles_version_unsupported" }
        val palettes = mutableMapOf<String, Map<String, String>>()
        val globals = mutableMapOf<String, Map<String, String>>()
        children(styles) {
            val tag = styles.name
            require(tag in setOf("palette", "global")) { "styles_element_unknown" }
            val mode = attributes(styles, setOf("mode")).getValue("mode")
            require(mode in if (tag == "palette") setOf("day", "night") else setOf("shared", "day", "night")) { "styles_mode_invalid" }
            val map = properties(styles)
            val target = if (tag == "palette") palettes else globals
            require(target.put(mode, map) == null) { "styles_mode_duplicate" }
        }
        endDocument(styles)
        val result = ThemeScene(SceneDocument(nodes, root["appearance"] ?: "auto", bool(root["shareDayNight"] ?: "false"), dayBackground, nightBackground),
            SceneStyles(SceneStyles.DAY + palettes["day"].orEmpty(), SceneStyles.NIGHT + palettes["night"].orEmpty(), globals["shared"].orEmpty(), globals["day"].orEmpty(), globals["night"].orEmpty()))
        SceneValidator.validate(result, theme.names())
        return result
    }

    fun write(metadata: ThemeMetadata, scene: ThemeScene, assets: Map<String, ByteArray> = emptyMap()): ThemePackage {
        SceneValidator.validate(scene, assets.keys)
        val document = xml("scene") { writer ->
            writer.attribute(null, "schemaVersion", "1").attribute(null, "appearance", scene.document.appearance)
                .attribute(null, "shareDayNight", scene.document.shareDayNight.toString())
            for ((mode, b) in listOf("day" to scene.document.dayBackground, "night" to scene.document.nightBackground)) {
                writer.startTag(null, "background").attribute(null, "mode", mode).attribute(null, "kind", b.kind)
                    .attribute(null, "color", b.color).attribute(null, "second", b.second).attribute(null, "image", b.image)
                    .attribute(null, "angle", b.angle.toString()).endTag(null, "background")
            }
            scene.document.nodes.forEach { writeNode(writer, it) }
        }
        val styles = xml("styles") { writer ->
            writer.attribute(null, "schemaVersion", "1")
            for ((mode, values) in listOf("day" to scene.styles.dayPalette, "night" to scene.styles.nightPalette)) {
                writer.startTag(null, "palette").attribute(null, "mode", mode); writeProperties(writer, values); writer.endTag(null, "palette")
            }
            for ((mode, values) in listOf("shared" to scene.styles.shared, "day" to scene.styles.day, "night" to scene.styles.night)) {
                writer.startTag(null, "global").attribute(null, "mode", mode); writeProperties(writer, values); writer.endTag(null, "global")
            }
        }
        return ThemePackageCodec.create(metadata, document, styles, assets)
    }

    private fun node(parser: XmlPullParser): SceneNode {
        val a = attributes(parser, setOf("id", "type", "name", "x", "y", "width", "height", "visible", "locked"))
        val properties = linkedMapOf<String, String>(); var day = emptyMap<String, String>(); var night = emptyMap<String, String>()
        val overrides = mutableSetOf<String>(); val nodes = mutableListOf<SceneNode>()
        // 深度上限在递归前检查，恶意嵌套不能先耗尽调用栈。
        require(parser.depth <= 10) { "scene_depth_exceeded" }
        children(parser) {
            when (parser.name) {
                "property" -> { val pair = property(parser); require(properties.put(pair.first, pair.second) == null) { "scene_property_duplicate" } }
                "override" -> {
                    val mode = attributes(parser, setOf("mode")).getValue("mode")
                    require(mode in setOf("day", "night") && overrides.add(mode)) { "scene_override_duplicate" }
                    if (mode == "day") day = properties(parser) else night = properties(parser)
                }
                "node" -> nodes.add(node(parser))
                else -> error("scene_node_child_unknown")
            }
        }
        return SceneNode(a.getValue("id"), a.getValue("type"), a["name"] ?: a.getValue("type"),
            SceneRect(a.getValue("x").toFloat(), a.getValue("y").toFloat(), a.getValue("width").toFloat(), a.getValue("height").toFloat()),
            properties, day, night, bool(a["visible"] ?: "true"), bool(a["locked"] ?: "false"), nodes)
    }

    private fun properties(parser: XmlPullParser): Map<String, String> = linkedMapOf<String, String>().apply {
        children(parser) { require(parser.name == "property"); val pair = property(parser); require(put(pair.first, pair.second) == null) { "scene_property_duplicate" } }
    }
    private fun property(parser: XmlPullParser): Pair<String, String> {
        val a = attributes(parser, setOf("key", "value")); empty(parser)
        return a.getValue("key") to a.getValue("value")
    }
    private fun parser(bytes: ByteArray, root: String): XmlPullParser = Xml.newPullParser().apply {
        setInput(ByteArrayInputStream(bytes), "UTF-8")
        require(nextTag() == XmlPullParser.START_TAG && name == root)
    }
    private fun attributes(parser: XmlPullParser, allowed: Set<String>): Map<String, String> = (0 until parser.attributeCount).associate {
        val name = parser.getAttributeName(it)
        require(name in allowed && parser.getAttributeNamespace(it).isNullOrEmpty()) { "scene_attribute_unknown" }
        name to parser.getAttributeValue(it)
    }
    private fun children(parser: XmlPullParser, child: () -> Unit) {
        val depth = parser.depth
        while (true) {
            val event = parser.nextTag()
            if (event == XmlPullParser.END_TAG) { require(parser.depth == depth); return }
            require(event == XmlPullParser.START_TAG && parser.depth == depth + 1)
            child()
            require(parser.eventType == XmlPullParser.END_TAG && parser.depth == depth + 1)
        }
    }
    private fun empty(parser: XmlPullParser) { require(parser.nextTag() == XmlPullParser.END_TAG) { "scene_unexpected_child" } }
    private fun endDocument(parser: XmlPullParser) { while (parser.nextToken() != XmlPullParser.END_DOCUMENT) require(parser.eventType in setOf(XmlPullParser.TEXT, XmlPullParser.COMMENT) && (parser.eventType != XmlPullParser.TEXT || parser.isWhitespace)) }
    private fun bool(value: String): Boolean { require(value in setOf("true", "false")); return value == "true" }
    private fun xml(root: String, content: (XmlSerializer) -> Unit): ByteArray {
        val out = StringWriter(); val writer = Xml.newSerializer()
        writer.setOutput(out); writer.startDocument("UTF-8", true); writer.startTag(null, root)
        content(writer); writer.endTag(null, root); writer.endDocument()
        return out.toString().toByteArray(Charsets.UTF_8)
    }
    private fun writeNode(writer: XmlSerializer, node: SceneNode) {
        writer.startTag(null, "node").attribute(null, "id", node.id).attribute(null, "type", node.type).attribute(null, "name", node.name)
            .attribute(null, "x", node.rect.x.toString()).attribute(null, "y", node.rect.y.toString())
            .attribute(null, "width", node.rect.width.toString()).attribute(null, "height", node.rect.height.toString())
            .attribute(null, "visible", node.visible.toString()).attribute(null, "locked", node.locked.toString())
        writeProperties(writer, node.properties)
        for ((mode, values) in listOf("day" to node.day, "night" to node.night)) if (values.isNotEmpty()) {
            writer.startTag(null, "override").attribute(null, "mode", mode); writeProperties(writer, values); writer.endTag(null, "override")
        }
        node.children.forEach { writeNode(writer, it) }; writer.endTag(null, "node")
    }
    private fun writeProperties(writer: XmlSerializer, properties: Map<String, String>) {
        properties.toSortedMap().forEach { (key, value) -> writer.startTag(null, "property").attribute(null, "key", key).attribute(null, "value", value).endTag(null, "property") }
    }
}
