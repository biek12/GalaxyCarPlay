package com.galaxy.autocluster.ui

import com.galaxy.autocluster.theme.*

internal data class ComponentPreset(val type: String,val title: String,val properties: Map<String,String>)

/** 组件库只预览样式；相同预设只在编辑器的“添加组件”入口实例化。 */
internal object ComponentPresets {
    private fun p(type: String,title: String,vararg properties: Pair<String,String>)=ComponentPreset(type,title,mapOf(*properties))
    val all=listOf(
        p("gauge","GT · 细针表盘","needleShape" to "line","needleWidth" to "4","faceVisible" to "true"),
        p("gauge","现代 · 指针与弧","indicator" to "both","faceVisible" to "false","arcWidth" to "6","trackVisible" to "true","accent" to "#42BAEE"),
        p("gauge","经典 · 楔形指针","needleShape" to "wedge","needleWidth" to "5","tickStyle" to "line","gloss" to "true"),
        p("ring","极简圆弧","numerals" to "false","trackVisible" to "true","arcWidth" to "8","intro" to "false"),
        p("ring","分度圆环","numerals" to "true","tickStyle" to "dot","trackVisible" to "true","intro" to "false"),
        p("ring","宽弧渐变","numerals" to "false","arcWidth" to "28","accent" to "#35B9C3","accentEnd" to "#78D99D","intro" to "false"),
        p("value","清晰数字","followMs" to "0","valueSize" to "100"),p("value","滚动读数","rolling" to "true","followMs" to "250"),p("value","窄体读数","font" to "condensed","valueSize" to "108"),
        p("bar","连续条形","maximum" to "100","source" to "vehicle.soc","unit" to "%"),p("bar","分段条形","maximum" to "100","source" to "vehicle.soc","unit" to "%","segments" to "12"),p("bar","阈值提示","maximum" to "100","source" to "vehicle.fuel","unit" to "%","warningBelow" to "20","criticalBelow" to "10","accent" to "#40BE97"),
        p("map","完整地图","cornerRadius" to "0"),p("map","细圆角地图","cornerRadius" to "12"),p("map","圆角地图卡片","cornerRadius" to "36"),
        p("lyrics","四行跟随","lines" to "4","activeRow" to "second"),p("lyrics","三行大字","lines" to "3","fontSize" to "30","activeSize" to "42"),p("lyrics","七行紧凑","lines" to "7","fontSize" to "20","activeSize" to "27","lineGap" to "6"),
        p("track","标题与歌手","align" to "left"),p("track","居中音乐信息","align" to "center","showAlbum" to "true"),p("track","超长标题滚动","overflow" to "marquee","align" to "left"),
        p("artwork","圆角封面","shape" to "rounded"),p("artwork","圆形封面","shape" to "circle"),p("artwork","方形封面","shape" to "rectangle"),
        p("progress","简洁进度","showTime" to "false","trackHeight" to "4"),p("progress","完整播放时间","showTime" to "true","trackHeight" to "5"),p("progress","醒目进度","showTime" to "true","trackHeight" to "12","accent" to "#40BE97"),
        p("tires","四轮胎压 · kPa","unit" to "kPa"),p("tires","四轮胎压 · bar","unit" to "bar"),p("tires","极简轮位","wheelLabels" to "false","unit" to "kPa"),
        p("pedals","踏板与方向盘","showSteering" to "true"),p("pedals","双踏板","showSteering" to "false","barWidth" to "30"),p("pedals","细线踏板","barWidth" to "8","font" to "condensed"),
        p("status","车辆状态","fields" to "gear,soc,fuel,evRange"),p("status","能源续航","fields" to "soc,evRange,fuelRange","fontSize" to "27"),p("status","简洁状态","showNames" to "false","fields" to "gear,soc,evRange"),
        p("navigation","完整导航信息"),p("navigation","距离与道路","showArrival" to "false"),p("navigation","到达与剩余","showRoad" to "false","showDistance" to "false"),
        p("clock","时间与日期"),p("clock","简洁时间","dateFormat" to "none","fontSize" to "46"),p("clock","含秒时间","timeFormat" to "HH:mm:ss","dateFormat" to "yyyy-MM-dd"),
        p("text","简洁文本","text" to "旅途，正在继续"),p("text","电量标注","text" to "纯电续航","icon" to "bolt"),p("text","音乐标注","text" to "正在播放","icon" to "music"),
        p("image","完整图片","fit" to "contain"),p("image","填满图片","fit" to "cover"),p("image","圆角图片","fit" to "cover","cornerRadius" to "40"),
        p("card","实色卡片"),p("card","描边卡片","background" to "#00000000","borderWidth" to "2"),p("card","半透明卡片","background" to "#A01B292C","cornerRadius" to "30"),
        p("fuel-range","燃油续航条"),p("fuel-range","燃油 · 蓝色条","accent" to "#42BAEE"),p("fuel-range","燃油 · 细条","trackHeight" to "3"),
        p("ev-range","纯电续航条"),p("ev-range","纯电 · 绿色条","accent" to "#40BE97"),p("ev-range","纯电 · 细条","trackHeight" to "3"),
        p("regen","回收 · 低/中/高/自动"),p("regen","回收 · 大字号","regenSize" to "22"),p("regen","回收 · 蓝绿色","regenColor" to "#30C4C6"),
        p("music-background","音乐 · 地图毛玻璃","backgroundStyle" to "map"),p("music-background","音乐 · 封面柔焦","backgroundStyle" to "artwork"),p("music-background","音乐 · 渐变背景","backgroundStyle" to "gradient"),
        p("pages","四页信息容器"),p("pages","淡入切页","pageTransition" to "fade"),p("pages","直接切页","pageTransition" to "none"),
        p("consumption","平均油耗"),p("consumption","平均电耗","source" to "vehicle.electricConsumption","unit" to "kWh/100km","label" to "平均电耗"),p("consumption","简洁能耗","valueSize" to "54"),
        p("indicators","灯光转向状态"),p("indicators","巡航状态图标","fields" to "cruiseState"),p("indicators","大尺寸状态图标","iconSize" to "36")
    )
    fun sample(preset: ComponentPreset): SceneNode {
        val spec=ComponentProperties.byType.getValue(preset.type)
        val extras=if(preset.type=="gauge"||preset.type=="ring")mapOf("intro" to "false")else emptyMap()
        if(preset.type=="pages")return CompositeComponents.pages("sample",SceneRect(16f,16f,spec.width,minOf(spec.height,500f)),preset.properties)
        return SceneNode("sample",preset.type,preset.title,SceneRect(16f,16f,spec.width,minOf(spec.height,500f)),preset.properties+extras)
    }
}
