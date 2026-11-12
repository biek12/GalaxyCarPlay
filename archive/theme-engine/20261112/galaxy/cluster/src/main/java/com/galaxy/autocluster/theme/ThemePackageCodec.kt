package com.galaxy.autocluster.theme

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 只在主题IO线程调用。ZIP不解压到外部路径；XML不执行外部声明或任意Android类。 */
internal object ThemePackageCodec {
    const val MAX_ENTRIES = 128
    const val MAX_XML_BYTES = 256 * 1024
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 24 * 1024 * 1024
    private val mandatory = setOf("manifest.xml", "scene.xml", "styles.xml")

    fun create(metadata: ThemeMetadata, scene: ByteArray, styles: ByteArray,
               assets: Map<String, ByteArray> = emptyMap()): ThemePackage {
        require(assets.keys.none { it in mandatory }) { "theme_reserved_file" }
        val files = assets + mapOf("manifest.xml" to manifest(metadata), "scene.xml" to scene, "styles.xml" to styles)
        return validate(files)
    }

    fun withMetadata(source: ThemePackage, metadata: ThemeMetadata): ThemePackage =
        validate(source.copyEntries() + ("manifest.xml" to manifest(metadata)))

    /** 不关闭调用方流，方便SAF/测试与原子存储分别管理FD寿命。 */
    fun read(input: InputStream): ThemePackage {
        val files = linkedMapOf<String, ByteArray>()
        val zip = ZipInputStream(object : java.io.FilterInputStream(input) { override fun close() = Unit })
        var total = 0
        var count = 0
        zip.use {
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= MAX_ENTRIES) { "theme_too_many_files" }
                val name = entry.name
                if (entry.isDirectory) {
                    require(validPath(name.removeSuffix("/"))) { "theme_path_invalid" }
                    require(zip.read() == -1) { "theme_directory_has_payload" }
                    zip.closeEntry(); continue
                }
                require(validPath(name) && validType(name)) { "theme_path_or_type_invalid" }
                require(name !in files) { "theme_duplicate_file" }
                val limit = if (name.endsWith(".xml")) MAX_XML_BYTES else MAX_FILE_BYTES
                require(entry.size <= limit) { "theme_file_too_large" }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    require(out.size() + read <= limit && total + read <= MAX_TOTAL_BYTES) { "theme_budget_exceeded" }
                    out.write(buffer, 0, read); total += read
                }
                zip.closeEntry()
                files[name] = out.toByteArray()
            }
        }
        return validate(files)
    }

    fun write(theme: ThemePackage, output: OutputStream) {
        // flush/finish ZIP，底层流仍归调用方；AtomicFile需要在其后完成sync与提交。
        val zip = ZipOutputStream(object : java.io.FilterOutputStream(output) {
            override fun close() { flush() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
        })
        zip.use {
            for ((name, bytes) in theme.copyEntries().toSortedMap()) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(bytes); zip.closeEntry()
            }
        }
    }

    fun bytes(theme: ThemePackage): ByteArray = ByteArrayOutputStream().also { write(theme, it) }.toByteArray()

    private fun validate(files: Map<String, ByteArray>): ThemePackage {
        require(files.keys.containsAll(mandatory)) { "theme_required_file_missing" }
        require(files.size <= MAX_ENTRIES && files.values.sumOf { it.size.toLong() } <= MAX_TOTAL_BYTES) { "theme_budget_exceeded" }
        files.forEach { (name, value) ->
            require(validPath(name) && validType(name)) { "theme_path_or_type_invalid" }
            require(value.size <= if (name.endsWith(".xml")) MAX_XML_BYTES else MAX_FILE_BYTES) { "theme_file_too_large" }
        }
        val attributes = xmlRoot(files.getValue("manifest.xml"), "cluster-package")
        require(attributes.keys == setOf("format", "schemaVersion", "id", "name", "revision")) { "theme_manifest_attributes_invalid" }
        require(attributes["format"] == "galaxy-cluster-theme" && attributes["schemaVersion"] == "1") { "theme_version_unsupported" }
        val metadata = ThemeMetadata(attributes.getValue("id"), attributes.getValue("name"), attributes.getValue("revision").toLong())
        xmlRoot(files.getValue("scene.xml"), "scene")
        xmlRoot(files.getValue("styles.xml"), "styles")
        return ThemePackage(metadata, files)
    }

    /** 这里只核对包结构和XML良构；组件字段/引用由SceneCompiler在应用前进一步校验。 */
    private fun xmlRoot(bytes: ByteArray, expected: String): Map<String, String> {
        require(bytes.size <= MAX_XML_BYTES) { "theme_xml_too_large" }
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var attributes: Map<String, String>? = null
        var roots = 0
        while (true) {
            val event = parser.nextToken()
            require(event != XmlPullParser.DOCDECL && event != XmlPullParser.PROCESSING_INSTRUCTION) { "theme_xml_declaration_forbidden" }
            if (event == XmlPullParser.START_TAG && parser.depth == 1) {
                require(++roots == 1 && parser.name == expected && parser.prefix.isNullOrEmpty() && parser.namespace.isNullOrEmpty()) { "theme_xml_root_invalid" }
                attributes = (0 until parser.attributeCount).associate { index ->
                    require(parser.getAttributeNamespace(index).isNullOrEmpty()) { "theme_xml_namespace_invalid" }
                    parser.getAttributeName(index) to parser.getAttributeValue(index)
                }
            }
            if (expected == "cluster-package") {
                require(!(event == XmlPullParser.START_TAG && parser.depth > 1)) { "theme_manifest_child_invalid" }
                require(!(event == XmlPullParser.TEXT && !parser.isWhitespace)) { "theme_manifest_text_invalid" }
            }
            if (event == XmlPullParser.END_DOCUMENT) break
        }
        return requireNotNull(attributes) { "theme_xml_root_missing" }
    }

    private fun validPath(name: String): Boolean = name.isNotEmpty() && name.length <= 200 &&
        name.none { it.isISOControl() || it == '\\' || it == ':' } &&
        name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun validType(name: String): Boolean = name in mandatory || name == "preview.png" ||
        (name.startsWith("assets/") && name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "ttf", "otf"))

    private fun manifest(meta: ThemeMetadata): ByteArray {
        fun escaped(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<cluster-package format=\"galaxy-cluster-theme\" schemaVersion=\"1\" id=\"${meta.id}\" name=\"${escaped(meta.name)}\" revision=\"${meta.revision}\"/>\n").toByteArray(Charsets.UTF_8)
    }
}
