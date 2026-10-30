package com.galaxy.autocluster.theme

/** 主题包只描述本机文件；内置身份由App登记，不能从导入包中获得。 */
internal data class ThemeMetadata(val id: String, val name: String, val revision: Long) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]{0,79}"))) { "theme_id_invalid" }
        require(name.isNotBlank() && name == name.trim() && name.length <= 80 && name.none { it.isISOControl() }) { "theme_name_invalid" }
        require(revision > 0) { "theme_revision_invalid" }
    }
}

/** 数组在边界复制，避免编辑草稿回写已应用场景或导出中的资源。 */
internal class ThemePackage internal constructor(val metadata: ThemeMetadata, entries: Map<String, ByteArray>) {
    private val files = entries.mapValues { it.value.copyOf() }
    fun names(): Set<String> = files.keys.toSet()
    fun content(name: String): ByteArray = requireNotNull(files[name]) { "theme_file_missing" }.copyOf()
    internal fun copyEntries(): Map<String, ByteArray> = files.mapValues { it.value.copyOf() }
}
