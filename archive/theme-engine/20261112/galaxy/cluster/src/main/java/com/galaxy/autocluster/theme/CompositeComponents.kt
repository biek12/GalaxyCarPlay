package com.galaxy.autocluster.theme

/** 仅创建可编辑Scene节点；没有整屏专用绘制分支，保存/导入后仍走通用组件树。 */
internal object CompositeComponents {
    fun pages(id: String,rect: SceneRect,properties: Map<String,String> = emptyMap()): SceneNode {
        fun node(suffix: String,type: String,x: Float,y: Float,w: Float,h: Float,props: Map<String,String> = emptyMap())=
            SceneNode("$id-$suffix",type,ComponentProperties.byType.getValue(type).title,SceneRect(x,y,w,h),props)
        val music=listOf(
            node("background","music-background",0f,31f,492f,357f,mapOf("backgroundStyle" to "artwork","backgroundColor" to "#EE071018","backgroundBlurRadius" to "23","backgroundOpacity" to ".85","backgroundRounded" to "false","backgroundEdgeFade" to "true","backgroundFadeWidth" to "70")),
            node("track","track",100f,28f,324f,70f,mapOf("align" to "center","titleSize" to "25","artistSize" to "18","lineHeight" to "1.05","gap" to "5")),
            node("lyrics","lyrics",100f,100f,324f,236f,mapOf("lines" to "4","activeRow" to "second","fontSize" to "25","activeSize" to "32","lineGap" to "12","lineHeight" to "1.125","lineColor" to "#ADB6BD","pastOpacity" to "1","futureOpacity" to "1","outerOpacity" to ".74","outerSizeScale" to ".96","edgeFade" to "false")),
            node("progress","progress",152f,355f,220f,48f,mapOf("trackHeight" to "4","fontSize" to "15","accent" to "#E1E7EA","trackColor" to "#4B5258")))
        fun page(key: String,title: String,children: List<SceneNode>)=SceneNode("$id-$key","card",title,SceneRect(0f,0f,600f,426f),mapOf("pageId" to key,"background" to "#00000000","cornerRadius" to "0"),children=children)
        val tire=listOf(node("oem-tires-label","text",40f,100f,460f,120f,mapOf("text" to "原厂胎压样式 · 待连接标定","fontSize" to "25")))
        val fuel=listOf(node("fuel-value","consumption",20f,40f,500f,300f,mapOf("source" to "vehicle.fuelConsumption","label" to "平均油耗","unit" to "L/100km","valueSize" to "58")))
        val electric=listOf(node("electric-value","consumption",20f,40f,500f,300f,mapOf("source" to "vehicle.electricConsumption","label" to "平均电耗","unit" to "kWh/100km","valueSize" to "58")))
        val base=SceneNode(id,"pages","信息分页",SceneRect(0f,0f,600f,426f),properties,children=listOf(page("music","音乐",music),page("tires","原厂胎压待标定",tire),page("fuel","油耗",fuel),page("electric","电耗",electric)))
        return SceneTree.resize(base,rect)
    }
}
