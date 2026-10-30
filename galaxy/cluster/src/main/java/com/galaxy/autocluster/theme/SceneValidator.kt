package com.galaxy.autocluster.theme

internal object SceneValidator {
    fun validate(scene: ThemeScene, files: Set<String>) {
        require(scene.document.appearance in setOf("auto", "day", "night")) { "scene_appearance_invalid" }
        for (palette in listOf(scene.styles.dayPalette, scene.styles.nightPalette)) {
            require(palette.size <= 32 && palette.keys.containsAll(SceneStyles.DAY.keys)) { "scene_palette_incomplete" }
            palette.forEach { (key, color) -> require(key.matches(Regex("[a-zA-Z][a-zA-Z0-9]{0,39}")) && color.matches(Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?"))) { "scene_palette_invalid" } }
        }
        val globals = ComponentProperties.all.flatMap { it.properties }.filter { it.styleable }.associateBy { it.key }
        listOf(scene.styles.shared, scene.styles.day, scene.styles.night).forEach { values -> checkProperties(values, globals, scene.styles, files, true) }
        val ids = mutableSetOf<String>(); var maps = 0
        fun nodes(values: List<SceneNode>, width: Float, height: Float, depth: Int) {
            require(depth <= 8) { "scene_depth_exceeded" }
            for (node in values) {
                require(ids.size < 96 && ids.add(node.id) && node.id.matches(Regex("[a-zA-Z][a-zA-Z0-9-]{0,79}"))) { "scene_node_id_invalid" }
                require(node.name.isNotBlank() && node.name.length <= 80) { "scene_node_name_invalid" }
                val rect = node.rect
                require(listOf(rect.x, rect.y, rect.width, rect.height).all { it.isFinite() } && rect.x >= 0f && rect.y >= 0f && rect.width >= 16f && rect.height >= 16f && rect.x + rect.width <= width + 0.01f && rect.y + rect.height <= height + 0.01f) { "scene_bounds_invalid" }
                val spec = requireNotNull(ComponentProperties.byType[node.type]) { "scene_component_unknown" }
                val properties = spec.properties.associateBy { it.key }
                checkProperties(node.properties, properties, scene.styles, files, false)
                checkProperties(node.day, properties, scene.styles, files, true)
                checkProperties(node.night, properties, scene.styles, files, true)
                val resolved = ComponentProperties.defaults(node.type) + node.properties
                if (resolved.containsKey("minimum")) require(resolved.getValue("minimum").toFloat() < resolved.getValue("maximum").toFloat()) { "scene_range_invalid" }
                if (node.type == "map") require(++maps == 1) { "scene_only_one_map" }
                require(node.type in setOf("card","pages") || node.children.isEmpty()) { "scene_child_requires_group" }
                if(node.type=="pages"){
                    val order=resolved.getValue("pageOrder").split(',').map { it.trim() }
                    require(order.size in 1..8&&order.distinct().size==order.size&&order.all { it.matches(Regex("[a-z][a-z0-9-]{0,39}")) }) { "scene_pages_order_invalid" }
                    val pages=node.children.map { it.properties["pageId"].orEmpty() }
                    require(resolved.getValue("initialPage") in order) { "scene_pages_initial_invalid" }
                    require(pages.toSet()==order.toSet()&&pages.size==order.size) { "scene_pages_children_invalid" }
                }
                nodes(node.children, rect.width, rect.height, depth + 1)
            }
        }
        nodes(scene.document.nodes, 1920f, 532f, 0)
        // 与公共合成契约一致，超预算明确拒绝，不能悄悄丢掉后面的毛玻璃区域。
        for (night in listOf(false, true)) {
            var regions = 0
            fun backdrops(values: List<SceneNode>) {
                for (node in values) {
                    if (!node.visible) continue
                    if (node.type in setOf("music-background", "pages")) {
                        val allowed = ComponentProperties.byType.getValue(node.type).properties.filter { it.styleable }.map { it.key }.toSet()
                        val mode = if (night && !scene.document.shareDayNight) scene.styles.night else scene.styles.day
                        val own = if (night && !scene.document.shareDayNight) node.night else node.day
                        val p = ComponentProperties.defaults(node.type) + scene.styles.shared.filterKeys { it in allowed } + mode.filterKeys { it in allowed } + node.properties + own
                        if (p["backgroundStyle"] == "map") require(++regions <= 8) { "scene_backdrop_budget_exceeded" }
                    }
                    backdrops(node.children)
                }
            }
            backdrops(scene.document.nodes)
        }
        for (background in listOf(scene.document.dayBackground, scene.document.nightBackground)) {
            require(background.kind in setOf("solid", "gradient", "image") && background.angle.isFinite()) { "scene_background_invalid" }
            listOf(background.color, background.second).forEach { checkColor(it, scene.styles) }
            if (background.kind == "image") require(background.image in files && isImage(background.image)) { "scene_background_image_missing" }
        }
    }
    private fun checkProperties(values: Map<String, String>, definitions: Map<String, PropertySpec>, styles: SceneStyles, files: Set<String>, override: Boolean) {
        for ((key, value) in values) {
            val spec = requireNotNull(definitions[key]) { "scene_property_unknown:$key" }
            require(!override || spec.styleable) { "scene_style_cannot_change_binding" }
            require(value.length <= 1024 && value.none { it == '\u0000' }) { "scene_property_too_long" }
            when (spec.kind) {
                PropertyKind.NUMBER -> require(value.toFloatOrNull()?.let { it.isFinite() && it >= spec.min && it <= spec.max } == true) { "scene_number_invalid:$key" }
                PropertyKind.COLOR -> checkColor(value, styles)
                PropertyKind.BOOLEAN -> require(value in setOf("true", "false")) { "scene_boolean_invalid" }
                PropertyKind.CHOICE -> require(spec.choices.any { it.first == value }) { "scene_choice_invalid" }
                PropertyKind.SOURCE -> require(value.isEmpty() || value.matches(Regex("(vehicle|media|navigation)\\.[a-zA-Z0-9_.-]{1,90}"))) { "scene_source_invalid" }
                PropertyKind.ASSET -> require(value.isEmpty() || (value in files && if (key == "fontAsset") value.endsWith(".ttf") || value.endsWith(".otf") else isImage(value))) { "scene_asset_missing" }
                PropertyKind.TEXT -> Unit
            }
            if (key in setOf("warningBelow", "criticalBelow", "warningAbove", "criticalAbove")) require(value.isEmpty() || value.toFloatOrNull()?.isFinite() == true) { "scene_threshold_invalid" }
            if (key in setOf("numeralSkip", "extraMarks")) require(value.isBlank() || value.split(',').let { it.size <= 40 && it.all { n -> n.trim().toFloatOrNull()?.isFinite() == true } }) { "scene_marks_invalid" }
        }
    }
    private fun isImage(path: String) = path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp")
    private fun checkColor(color: String, styles: SceneStyles) {
        require(if (color.startsWith('@')) color.drop(1) in styles.dayPalette && color.drop(1) in styles.nightPalette else color.matches(Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?"))) { "scene_color_invalid" }
    }
}
