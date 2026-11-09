package com.galaxy.autocluster.theme

/** 显式同步已编辑主题：只补缺失的可见能量组件，保留全部原节点/样式/素材与用户位置。 */
internal object EnergyThemeSync {
    fun merge(theme: ThemePackage): ThemePackage {
        val scene=SceneXml.read(theme)
        val visible=mutableListOf<SceneNode>()
        fun collect(nodes: List<SceneNode>){for(node in nodes)if(node.visible){visible.add(node);collect(node.children)}}
        collect(scene.document.nodes)
        val used=SceneTree.flatten(scene.document.nodes).map{it.id}.toMutableSet()
        val additions=mutableListOf<SceneNode>()
        for((type,x,color)in listOf(Triple("ev-range",1265f,"#2BB45E"),Triple("fuel-range",1509f,"#009DFF"))){
            if(visible.any{it.type==type})continue
            var id="synced-$type";while(id in used)id+="-new";used.add(id)
            additions.add(SceneNode(id,type,if(type=="ev-range")"电量与纯电续航"else"油量与燃油续航",
                SceneRect(x,460f,130f,48f),mapOf("rangeStyle" to "l6","fontSize" to "26","unitSize" to "22",
                    "trackHeight" to "10","accent" to color,"trackColor" to "#353839")))
        }
        if(additions.isEmpty())return theme
        return SceneXml.write(theme.metadata.copy(revision=Math.addExact(theme.metadata.revision,1)),
            scene.copy(document=scene.document.copy(nodes=scene.document.nodes+additions)),
            theme.copyEntries().filterKeys{it !in setOf("manifest.xml","scene.xml","styles.xml")})
    }
}
