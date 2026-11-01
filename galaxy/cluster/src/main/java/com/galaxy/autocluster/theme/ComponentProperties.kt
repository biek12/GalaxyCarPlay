package com.galaxy.autocluster.theme

internal enum class PropertyKind { NUMBER, COLOR, BOOLEAN, CHOICE, TEXT, SOURCE, ASSET }
internal data class PropertySpec(val key: String, val label: String, val section: String, val default: String,
    val kind: PropertyKind, val min: Float = -100000f, val max: Float = 100000f,
    val choices: List<Pair<String, String>> = emptyList(), val styleable: Boolean = true)
internal data class ComponentSpec(val type: String, val title: String, val width: Float, val height: Float, val properties: List<PropertySpec>)

/** 属性注册表同时供编译校验、原生属性页与组件绘制使用；每类只暴露其实际绘制参数。 */
internal object ComponentProperties {
    private fun number(k: String, l: String, d: Number, s: String, min: Float = 0f, max: Float = 2000f, style: Boolean = true) =
        PropertySpec(k, l, s, d.toString(), PropertyKind.NUMBER, min, max, styleable = style)
    private fun color(k: String, l: String, d: String, s: String = "配色") = PropertySpec(k, l, s, d, PropertyKind.COLOR)
    private fun bool(k: String, l: String, d: Boolean, s: String) = PropertySpec(k, l, s, d.toString(), PropertyKind.BOOLEAN)
    private fun text(k: String, l: String, d: String, s: String, style: Boolean = true) = PropertySpec(k, l, s, d, PropertyKind.TEXT, styleable = style)
    private fun source(k: String = "source", l: String = "数据来源", d: String = "vehicle.displaySpeed", s: String = "数据") = PropertySpec(k, l, s, d, PropertyKind.SOURCE, styleable = false)
    private fun asset(k: String, l: String, s: String) = PropertySpec(k, l, s, "", PropertyKind.ASSET)
    private fun choice(k: String, l: String, d: String, s: String, vararg values: Pair<String, String>) = PropertySpec(k, l, s, d, PropertyKind.CHOICE, choices = values.toList())
    private val type = listOf(
        choice("font", "字体", "sans", "文字", "sans" to "无衬线", "rounded" to "圆体", "condensed" to "窄体", "mono" to "等宽", "asset" to "主题字体"),
        asset("fontAsset", "主题字体文件", "文字"), number("fontWeight", "字重", 400, "文字", 100f, 900f),
        number("letterSpacing", "字距", 0, "文字", -0.1f, 0.5f), color("foreground", "主要文字", "@foreground"), color("muted", "次要文字", "@muted"),
    )
    private val base = listOf(number("opacity", "透明度", 1, "外观", 0f, 1f))
    private val alignment = choice("align", "对齐", "center", "文字", "left" to "左对齐", "center" to "居中", "right" to "右对齐")
    private val range = listOf(source(), number("minimum", "最小值", 0, "数据", -100000f, 100000f, false),
        number("maximum", "最大值", 260, "数据", -100000f, 100000f, false), text("unit", "单位", "km/h", "数据", false), number("decimals", "小数位", 0, "数据", 0f, 3f, false))
    private val zones = listOf(
        text("warningBelow", "低于此值提醒（空为关闭）", "", "阈值"), text("criticalBelow", "低于此值严重提醒", "", "阈值"),
        text("warningAbove", "高于此值提醒", "", "阈值"), text("criticalAbove", "高于此值严重提醒", "", "阈值"),
        color("warningColor", "提醒色", "@warning", "阈值"), color("criticalColor", "严重提醒色", "@critical", "阈值"),
    )
    private val follow = listOf(number("followMs", "数值跟随时长（ms）", 160, "动画", 0f, 1500f),
        choice("easing", "缓动", "ease-out", "动画", "linear" to "线性", "ease-out" to "平滑减速", "smooth" to "平滑进出"))
    private val regeneration = listOf(
        bool("regenVisible","显示回收档位与功率",false,"动能回收"),
        source("regenLevelSource","回收档位来源","vehicle.regenLevel","动能回收"),
        source("regenPowerSource","独立回收功率来源（kW）","vehicle.regenPowerKw","动能回收"),
        number("regenMaximum","回收功率满量程（kW）",120,"动能回收",1f,500f),
        choice("powerDisplay","功率显示方式","regen-only","动能回收","regen-only" to "仅回收","bipolar" to "驱动与回收"),
        choice("powerInput","功率数据合同","independent","动能回收","independent" to "独立驱动/回收幅值","signed" to "已核实的有符号kW"),
        source("drivePowerSource","独立驱动功率来源（kW）","vehicle.drivePowerKw","动能回收"),
        source("signedPowerSource","有符号功率来源（kW）","vehicle.tractionPowerKw","动能回收"),
        choice("powerPositive","已核实正号代表","drive","动能回收","drive" to "驱动","regen" to "回收"),
        number("driveMaximum","驱动功率满量程（kW）",200,"动能回收",1f,1000f),
        color("driveColor","驱动颜色","#6FBEF5","动能回收"),
        color("regenColor","回收颜色","#56D9AE","动能回收"),
        number("regenRadius","回收弧半径比例",.96,"动能回收",.2f,1f),
        number("regenWidth","回收弧宽度",5,"动能回收",1f,30f),
        number("regenY","回收文字纵向偏移",171,"动能回收",-180f,190f),
        number("regenSize","回收字号",17,"动能回收",8f,60f))
    private val musicBackground = listOf(
        choice("backgroundStyle","背景样式","none","背景","none" to "透明","solid" to "纯色","gradient" to "渐变","artwork" to "封面","map" to "地图毛玻璃"),
        color("backgroundColor","背景颜色","#99101B24","背景"),color("backgroundEndColor","渐变终点","#33101B24","背景"),
        bool("backgroundRounded","背景使用圆角",true,"背景"),number("backgroundRadius","背景圆角",24,"背景",0f,200f),
        bool("backgroundEdgeFade","背景边缘渐隐",false,"背景"),number("backgroundFadeWidth","背景渐隐宽度",65,"背景",1f,200f),
        bool("backgroundBlur","背景模糊",true,"背景"),number("backgroundBlurRadius","模糊半径",12,"背景",0f,32f),
        number("backgroundOpacity","背景透明度",.7,"背景",0f,1f),number("backgroundInset","背景内缩",0,"背景",0f,200f))
    private val gauge = range + type + zones + follow + regeneration + listOf(
        choice("indicator", "显示方式", "needle", "圆弧", "needle" to "指针", "arc" to "填充弧", "both" to "指针与弧", "scale" to "仅刻度"),
        number("startAngle", "起始角度", 135, "圆弧", -360f, 360f), number("sweepAngle", "扫过角度", 270, "圆弧", 1f, 360f),
        number("radius", "圆弧半径比例", 0.88, "圆弧", 0.1f, 1f), number("arcWidth", "圆弧线宽", 12, "圆弧", 1f, 200f),
        bool("trackVisible", "显示底轨", false, "圆弧"), color("trackColor", "底轨色", "@track", "圆弧"),
        color("accent", "指示弧色", "@accent", "圆弧"), color("accentEnd", "弧渐变终点", "@accent", "圆弧"), bool("roundEnds", "圆角端点", true, "圆弧"),
        choice("tickStyle", "刻度样式", "line", "刻度", "line" to "线条", "dot" to "圆点", "none" to "无刻度"),
        number("majorStep", "主刻度间隔", 20, "刻度", 0.1f, 100000f), number("minorCount", "每段次刻度数", 1, "刻度", 0f, 10f),
        number("tickRadius", "刻度半径比例", 0.91, "刻度", 0.1f, 1f), number("majorLength", "主刻度长度", 18, "刻度", 1f, 100f),
        number("minorLength", "次刻度长度", 8, "刻度", 1f, 100f), number("tickWidth", "主刻度线宽", 2.5, "刻度", 0.5f, 20f),
        number("minorWidth", "次刻度线宽", 1, "刻度", 0.5f, 20f), color("tickColor", "刻度色", "@muted", "刻度"),
        bool("litTicks", "点亮当前值之前的刻度", false, "刻度"), color("litColor", "点亮色", "@accent", "刻度"),
        number("secondTickStep", "外圈第二组间隔（0关闭）", 0, "第二组刻度", 0f, 100000f), number("secondTickLength", "外圈刻度长度", 5, "第二组刻度", 1f, 50f), color("secondTickColor", "外圈刻度色", "@track", "第二组刻度"),
        bool("numerals", "显示刻度数字", true, "刻度数字"), number("numeralRadius", "数字半径比例", 0.72, "刻度数字", 0.1f, 1.2f),
        number("numeralSize", "数字字号", 22, "刻度数字", 8f, 120f), number("numeralDivisor", "数字除数", 1, "刻度数字", 0.001f, 10000f),
        text("numeralSkip", "不显示的刻度值（逗号分隔）", "", "刻度数字"), bool("numeralRotate", "沿圆弧旋转数字", false, "刻度数字"), color("numeralColor", "刻度数字色", "@foreground", "刻度数字"),
        choice("needleShape", "指针形状", "line", "指针", "line" to "细线", "wedge" to "楔形", "outline" to "轮廓", "bars" to "双线"),
        number("needleLength", "指针长度比例", 0.85, "指针", 0.1f, 1f), number("needleTail", "尾部长度", 18, "指针", 0f, 80f),
        number("needleWidth", "指针宽度", 4, "指针", 1f, 40f), color("needleColor", "指针色", "@foreground", "指针"),
        number("hubRadius", "轴心半径", 9, "指针", 0f, 40f), bool("hubRing", "空心轴心", false, "指针"), color("hubColor", "轴心色", "@foreground", "指针"),
        bool("faceVisible", "显示盘面", true, "盘面"), color("faceColor", "盘面色", "@panel", "盘面"),
        number("faceRadius", "盘面半径比例", 0.98, "盘面", 0.1f, 1f), number("bezelWidth", "表圈线宽", 2, "盘面", 0f, 30f), color("bezelColor", "表圈色", "@track", "盘面"),
        bool("gloss", "表面柔光", false, "盘面"), number("valueSize", "中心读数字号", 56, "中心读数", 8f, 180f),
        number("valueY", "中心读数Y偏移", 80, "中心读数", -180f, 180f), bool("valueVisible", "显示实时数字", true, "中心读数"),
        number("unitSize", "单位字号", 18, "中心读数", 8f, 60f), source("secondarySource", "辅助读数来源", "vehicle.evRange", "辅助读数"),
        text("secondaryUnit", "辅助单位", "km", "辅助读数"), number("secondarySize", "辅助字号", 20, "辅助读数", 8f, 80f),
        source("leftSatellite", "左侧辅助弧来源", "", "辅助圆弧"), source("rightSatellite", "右侧辅助弧来源", "", "辅助圆弧"),
        number("satelliteMaximum", "辅助弧量程上限", 100, "辅助圆弧", 1f, 10000f), number("satelliteWidth", "辅助弧线宽", 5, "辅助圆弧", 1f, 25f),
        color("satelliteColor", "辅助弧色", "@accent", "辅助圆弧"), source("cruiseSource", "巡航目标速度", "vehicle.cruiseSetSpeed", "巡航"),
        source("cruiseStateSource", "巡航状态来源", "vehicle.cruiseState", "巡航"),
        bool("cruiseVisible", "显示巡航信息", true, "巡航"),
        color("cruiseStandbyColor", "开启未激活颜色", "#FFFFFF", "巡航"),
        number("cruiseY", "巡航文字纵向偏移", 32, "巡航", -180f, 180f),
        number("cruiseSize", "巡航字号", 22, "巡航", 8f, 80f),
        number("cruiseDotRadius", "目标速度圆点半径", 8, "巡航", 1f, 24f),
        choice("cruiseMarkerMode","巡航弧语义","difference","巡航","difference" to "设定值到实际指针的差值弧","target" to "设定速度短标记","none" to "只显示目标圆点"),
        number("cruiseMarkerSweep", "目标短弧角度", 8, "巡航", 0f, 30f),
        number("cruiseDotPosition", "圆点半径比例", .89, "巡航", .4f, 1.1f),
        source("limiterSource", "限速器目标来源", "", "标记"), source("roadLimitSource", "道路限速来源", "", "标记"),
        color("cruiseColor", "激活巡航颜色", "#29D953", "巡航"), color("limiterColor", "限速器标记色", "@warning", "标记"),
        text("extraMarks", "额外标记值（逗号分隔）", "", "标记"), bool("intro", "首次显示扫表", true, "动画"), number("introMs", "启动扫表时长（ms）", 1200, "动画", 200f, 4000f),
    )
    private val textStyle = type + listOf(number("fontSize", "字号", 32, "文字", 8f, 180f), alignment,
        number("lineHeight", "行高比例", 1.4, "文字", 1f, 2.5f))
    private fun rangeBar(range: String,level: String,label: String,icon: String)=type+listOf(
        source("source","续航来源","vehicle.$range"),source("levelSource","剩余百分比来源","vehicle.$level"),
        text("label","名称",label,"文字",false),choice("rangeIcon","图标",icon,"外观","fuel" to "燃油","bolt" to "电池"),
        number("fontSize","字号",22,"文字",8f,80f),number("trackHeight","条高",5,"外观",1f,30f),
        color("accent","进度色","@accent","外观"),color("trackColor","底轨色","@track","外观"))
    private fun component(type: String, title: String, w: Float, h: Float, props: List<PropertySpec>) = ComponentSpec(type, title, w, h, (base + listOf(text("pageId","所属页ID（容器子层）","","分页",false)) + props).associateBy { it.key }.values.toList())

    val all: List<ComponentSpec> = listOf(
        component("map", "完整导航地图", 950f, 450f, listOf(bool("rounded","使用圆角",true,"窗口"),number("cornerRadius", "窗口圆角", 12, "窗口", 0f, 120f),
            number("videoOpacity","地图背景亮度/透明度",1,"地图背景",0f,1f),bool("blurEnabled","地图虚化",false,"地图背景"),choice("blurStyle","虚化方式","edges","地图背景","edges" to "仅边缘","uniform" to "整体"),
            number("blurRadius","虚化半径",12,"地图背景",0f,32f),number("edgeWidth","边缘虚化宽度",100,"地图背景",1f,400f),
            bool("edgeFade","边缘渐隐",false,"地图背景"),number("edgeFadeWidth","边缘渐隐范围",90,"地图背景",1f,400f))),
        component("value", "数字读数", 340f, 210f, range.filterNot { it.key in setOf("minimum","maximum") } + textStyle + zones + follow + listOf(text("label", "标题", "速度", "文字"), number("valueSize", "读数字号", 92, "文字", 12f, 220f), number("unitSize", "单位字号", 24, "文字", 8f, 80f), choice("unitPosition","单位位置","below","文字","below" to "下方","inline" to "同一行"), bool("rolling", "数字滚动过渡", false, "动画"))),
        component("gauge", "GT指针仪表", 490f, 490f, gauge),
        component("ring", "圆弧仪表", 300f, 300f, gauge.filter { it.section !in setOf("指针", "第二组刻度", "辅助圆弧", "标记", "巡航", "动能回收") }.map { if (it.key == "indicator") it.copy(default = "arc", choices=it.choices.filter { value->value.first in setOf("arc","scale") }) else if (it.key == "majorStep") it.copy(default = "25") else if (it.key == "maximum") it.copy(default = "100") else if (it.key == "source") it.copy(default = "vehicle.soc") else if (it.key == "unit") it.copy(default = "%") else it }),
        component("bar", "条形仪表", 430f, 95f, range + zones + follow + type + listOf(choice("direction", "方向", "horizontal", "条形", "horizontal" to "横向", "vertical" to "纵向"), color("accent", "填充色", "@accent"), color("trackColor", "底轨色", "@track"), number("segments", "分段数（0连续）", 0, "条形", 0f, 60f), number("gap", "段间距", 4, "条形", 0f, 20f), number("cornerRadius", "圆角", 4, "条形", 0f, 80f), bool("showValue", "显示数值与单位", true, "文字"), number("fontSize", "字号", 24, "文字", 8f, 80f))),
        component("tires", "四轮胎压", 430f, 360f, type + listOf(number("fontSize", "读数字号", 32, "文字", 8f, 100f)) + listOf(choice("unit", "显示单位", "kPa", "数据", "kPa" to "kPa", "bar" to "bar", "psi" to "psi"), number("low", "低压提醒阈值（kPa）", 190, "数据", 0f, 400f), number("high", "高压提醒阈值（kPa）", 300, "数据", 0f, 500f), color("warningColor", "提醒色", "@warning"), color("carColor", "车辆轮廓色", "@track"), bool("wheelLabels", "显示轮位名称", true, "文字"))),
        component("pedals", "踏板与方向盘", 430f, 300f, type + listOf(number("fontSize", "文字字号", 32, "文字", 8f, 100f)) + listOf(color("acceleratorColor", "油门色", "#3EBB92"), color("brakeColor", "刹车色", "@warning"), bool("showSteering", "显示方向盘角度", true, "内容"), number("barWidth", "进度条宽度", 20, "内容", 4f, 80f))),
        component("status", "车辆状态行", 700f, 70f, type + listOf(number("fontSize", "读数字号", 32, "文字", 8f, 100f)) + listOf(text("fields", "显示字段（逗号分隔）", "gear,soc,fuel,evRange", "数据", false), bool("showNames", "显示字段名称", true, "文字"), number("spacing", "项目间距", 18, "文字", 0f, 100f))),
        component("artwork", "专辑封面", 240f, 240f, listOf(choice("shape", "形状", "rounded", "图像", "rectangle" to "矩形", "rounded" to "圆角", "circle" to "圆形"), number("cornerRadius", "圆角", 20, "图像", 0f, 120f), number("fadeMs", "换封面淡入时长（ms）", 240, "动画", 0f, 1500f), color("placeholderColor", "无封面底色", "@track"))),
        component("music-background","音乐背景区域",580f,380f,musicBackground),
        component("regen","动能回收",220f,120f,regeneration.filterNot { it.key=="regenVisible" }+type),
        component("fuel-range","燃油续航条",230f,60f,rangeBar("fuelRange","fuel","燃油","fuel")),
        component("ev-range","纯电续航条",230f,60f,rangeBar("evRange","soc","纯电","bolt")),
        component("pages","信息分页容器",600f,426f,musicBackground+listOf(
            text("pageOrder","页序ID（逗号分隔）","music,tires,fuel,electric","分页",false),
            choice("initialPage","初始页/草稿预览页","music","分页","music" to "音乐","tires" to "胎压","fuel" to "油耗","electric" to "电耗"),
            choice("pageTransition","切页动画","slide-fade","分页","slide-fade" to "滑动与淡入","fade" to "淡入","none" to "直接切换"),
            number("pageDuration","切页时长（ms）",260,"分页",0f,1000f),number("pageDistance","切页距离",28,"分页",0f,150f),
            bool("pageDots","竖向页码点",true,"分页"),color("pageDotColor","当前页码色","@foreground","分页"))),
        component("consumption","油耗/电耗",540f,300f,type+listOf(source("source","平均值来源","vehicle.fuelConsumption"),text("label","名称","平均油耗","文字",false),text("unit","单位","L/100km","数据",false),number("valueSize","字号",72,"文字",12f,160f),bool("showChart","显示实际样本趋势",true,"图表"))),
        component("indicators","状态图标行",620f,45f,listOf(text("fields","状态字段列表","leftTurn,lowBeam,highBeam,rightTurn","数据",false),number("iconSize","图标大小",28,"外观",12f,80f),color("onColor","开启色","#29D953","外观"),color("offColor","关闭色","#31414A","外观"))),
        component("track", "曲目信息", 580f, 160f, textStyle.filterNot { it.key=="fontSize" } + listOf(number("titleSize", "标题字号", 38, "文字", 8f, 120f), number("artistSize", "歌手字号", 26, "文字", 8f, 100f), bool("showAlbum", "显示专辑", false, "内容"), number("gap", "行间距", 12, "文字", 0f, 80f), choice("overflow", "超长文字", "ellipsis", "文字", "ellipsis" to "省略", "marquee" to "往返滚动"), number("marqueeSpeed", "滚动速度（设计像素/秒）", 24, "动画", 5f, 100f))),
        component("progress", "音乐进度", 580f, 90f, type.filterNot { it.key=="foreground" } + listOf(number("trackHeight", "进度条高度", 5, "进度", 1f, 30f), color("accent", "已播色", "@accent"), color("trackColor", "底轨色", "@track"), bool("showTime", "显示播放时间", true, "文字"), number("fontSize", "时间字号", 20, "文字", 8f, 80f), bool("roundEnds", "圆角端点", true, "进度"))),
        component("lyrics", "多行歌词", 360f, 244f, textStyle.filterNot { it.key in setOf("foreground","muted") }.map { if(it.key=="lineHeight")it.copy(default="1.125")else it } + listOf(choice("lines", "可见行数", "4", "排版", "3" to "3行", "4" to "4行", "5" to "5行", "7" to "7行"), choice("activeRow", "当前行位置", "auto", "排版", "auto" to "按行数自动", "center" to "居中", "second" to "第二行（上方一行）"), number("fontSize", "普通行字号", 25, "文字", 8f, 100f), number("activeSize", "当前行字号", 32, "文字", 8f, 120f), number("lineGap", "行距", 7, "排版", 0f, 80f), number("pastOpacity", "已播行透明度", 1, "排版", 0f, 1f), number("futureOpacity", "未播行透明度", 1, "排版", 0f, 1f), number("outerOpacity", "最外行相对透明度", 0.74, "排版", 0f, 1f), number("outerSizeScale", "最外行字号比例", 0.96, "排版", 0.5f, 1f), color("activeColor", "当前行颜色", "@foreground"), color("lineColor", "普通行颜色", "@muted"), choice("transition", "切行方式", "scroll", "动画", "scroll" to "滚动与强调", "fade" to "淡入淡出", "none" to "直接切换"), number("transitionMs", "切行时长（ms）", 420, "动画", 0f, 1500f), choice("easing", "切行缓动", "smooth", "动画", "linear" to "线性", "ease-out" to "平滑减速", "smooth" to "平滑进出"), bool("edgeFade", "边缘淡化", false, "排版"), text("emptyText", "无歌词提示", "暂无同步歌词", "内容", false))),
        component("navigation", "导航信息", 520f, 180f, textStyle + listOf(choice("navigationLayout","布局","full","内容","full" to "导航与行程","guidance" to "转向指引","trip" to "行程三列"),bool("showArrow","显示已知转向图形",true,"内容"),number("arrowSize","转向图形大小",64,"内容",16f,160f),bool("showRoad", "显示道路", true, "内容"), bool("showDistance", "显示距离", true, "内容"), bool("showArrival", "显示到达与剩余", true, "内容"), color("accent", "方向强调色", "@accent"))),
        component("clock", "日期与时间", 330f, 110f, textStyle + listOf(choice("timeFormat", "时间格式", "HH:mm", "内容", "HH:mm" to "24小时", "hh:mm a" to "12小时", "HH:mm:ss" to "含秒"), choice("dateFormat", "日期格式", "MM月dd日 EEE", "内容", "MM月dd日 EEE" to "月日 星期", "yyyy-MM-dd" to "年月日", "none" to "不显示日期"), number("dateSize", "日期字号", 20, "文字", 8f, 60f))),
        component("text", "文本与图标", 400f, 90f, textStyle.filterNot { it.key in setOf("muted","lineHeight") } + listOf(text("text", "文字", "自定义文字", "内容", false), choice("icon", "内置图标", "none", "内容", "none" to "无", "bolt" to "电量", "fuel" to "燃油", "music" to "音乐", "arrow" to "方向", "temperature" to "温度"), number("iconSize", "图标大小", 32, "文字", 8f, 120f))),
        component("image", "图片", 300f, 200f, listOf(asset("image", "图片文件", "图像"), choice("fit", "缩放方式", "contain", "图像", "contain" to "完整适配", "cover" to "填满裁剪"), number("cornerRadius", "圆角", 12, "图像", 0f, 120f))),
        component("card", "卡片与分组", 600f, 430f, listOf(color("background", "背景色", "@panel"), color("borderColor", "边框色", "@track"), number("borderWidth", "边框宽度", 0, "外观", 0f, 20f), number("cornerRadius", "圆角", 22, "外观", 0f, 200f), bool("clip", "裁剪内部组件", true, "分组"))),
    )
    val byType = all.associateBy { it.type }
    fun defaults(type: String) = byType.getValue(type).properties.associate { it.key to it.default }
}
