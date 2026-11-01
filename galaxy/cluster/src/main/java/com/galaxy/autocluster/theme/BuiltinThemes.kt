package com.galaxy.autocluster.theme

/** 自写布局与素材；GT仅借鉴盘面分层/功能组织，不包含站点代码、SF字体或厂商品牌。 */
internal object BuiltinThemes {
    private fun node(id: String,type: String,x: Float,y: Float,w: Float,h: Float,props: Map<String,String> = emptyMap()) =
        SceneNode(id,type,ComponentProperties.byType.getValue(type).title,SceneRect(x,y,w,h),props)
    private val gt = listOf(
        node("speed","gauge",20f,22f,488f,488f,mapOf("source" to "vehicle.displaySpeed","majorStep" to "20","minorCount" to "1","needleShape" to "line","needleWidth" to "4","valueSize" to "60","valueY" to "112","secondarySource" to "vehicle.evRange","secondaryUnit" to "km")),
        node("map","map",530f,35f,860f,310f),
        node("rpm","gauge",1410f,22f,488f,488f,mapOf("source" to "vehicle.rpm","minimum" to "0","maximum" to "8000","majorStep" to "1000","numeralDivisor" to "1000","unit" to "rpm","cruiseVisible" to "false","valueSize" to "42","secondarySource" to "vehicle.gear","secondaryUnit" to "","warningAbove" to "6500","criticalAbove" to "7500")),
        node("lyrics","lyrics",550f,354f,820f,130f,mapOf("lines" to "3","fontSize" to "22","activeSize" to "28","lineGap" to "6","lineHeight" to "1.1")),
        node("progress","progress",580f,485f,760f,37f,mapOf("showTime" to "false","trackHeight" to "3")),
        node("clock","clock",800f,0f,320f,45f,mapOf("fontSize" to "24","dateFormat" to "none")),
    )
    private val simple=listOf(
        node("map","map",490f,16f,960f,360f),
        node("speed-card","card",25f,38f,430f,450f),
        node("speed","value",60f,100f,360f,285f,mapOf("label" to "速度","fontSize" to "24","valueSize" to "116","followMs" to "0")),
        node("energy-card","card",1480f,38f,415f,450f),
        node("energy","ring",1510f,65f,355f,355f,mapOf("source" to "vehicle.soc","minimum" to "0","maximum" to "100","majorStep" to "25","unit" to "%","secondarySource" to "vehicle.evRange","intro" to "false")),
        node("track","track",550f,390f,800f,100f,mapOf("titleSize" to "28","artistSize" to "20","lineHeight" to "1.15","align" to "center")),
        node("progress","progress",560f,495f,780f,30f,mapOf("showTime" to "false")),
    )
    private val neon=listOf(
        node("map","map",510f,15f,1385f,340f),node("speed-card","card",25f,20f,450f,492f),
        node("speed","gauge",40f,25f,420f,420f,mapOf("indicator" to "both","accent" to "#38BFF2","needleColor" to "#EEFAFF","faceColor" to "#0B1724","trackVisible" to "true","arcWidth" to "6","secondarySource" to "vehicle.gear","secondaryUnit" to "")),
        node("track","track",545f,390f,420f,120f,mapOf("titleSize" to "28","artistSize" to "20","align" to "left")),
        node("lyrics","lyrics",990f,360f,850f,150f,mapOf("lines" to "3","fontSize" to "23","activeSize" to "29","activeColor" to "#38BFF2","lineGap" to "6","lineHeight" to "1.1")),
    )
    private val reference=listOf(
        node("map-background","map",475f,64f,1390f,448f,mapOf("videoOpacity" to ".45","rounded" to "false","blurEnabled" to "true","blurStyle" to "edges","blurRadius" to "15","edgeWidth" to "125","edgeFade" to "true","edgeFadeWidth" to "95")),
        node("time","clock",40f,8f,160f,40f,mapOf("dateFormat" to "none","fontSize" to "25")),
        node("temperature","value",360f,6f,190f,43f,mapOf("source" to "vehicle.outsideTemperature","label" to "","unit" to "°C","unitPosition" to "inline","valueSize" to "25","unitSize" to "16","followMs" to "0")),
        node("top-status","indicators",690f,10f,530f,36f,mapOf("fields" to "leftTurn,lowBeam,highBeam,driverSeatbeltWarning,parkingBrake,positionLights,cruiseState,rightTurn")),
        node("gear","value",1628f,8f,72f,40f,mapOf("source" to "vehicle.gear","label" to "","unit" to "","valueSize" to "27","followMs" to "0")),
        node("drive-mode","value",1705f,8f,145f,40f,mapOf("source" to "vehicle.driveMode","label" to "","unit" to "","valueSize" to "22","foreground" to "@muted","followMs" to "0")),
        node("speed","gauge",35f,64f,455f,455f,mapOf("source" to "vehicle.displaySpeed","indicator" to "needle","faceVisible" to "false","bezelWidth" to "1","needleWidth" to "3.5","valueSize" to "62","valueY" to "108","secondarySource" to "","cruiseY" to "36","regenVisible" to "true","powerDisplay" to "bipolar","regenY" to "171","regenSize" to "16","regenRadius" to ".96")),
        node("navigation","navigation",695f,105f,570f,225f,mapOf("navigationLayout" to "guidance","showArrival" to "false","fontSize" to "29","lineHeight" to "1.4","align" to "left","arrowSize" to "65")),
        node("trip","navigation",700f,355f,535f,92f,mapOf("navigationLayout" to "trip","fontSize" to "27")),
        CompositeComponents.pages("information",SceneRect(1248f,72f,600f,426f)),
        node("fuel-range","fuel-range",1000f,460f,225f,56f).copy(name="燃油续航 · 原车位置样式待标定",visible=false),
        node("ev-range","ev-range",730f,460f,225f,56f).copy(name="纯电续航 · 原车位置样式待标定",visible=false),
    )
    fun packages(): List<ThemePackage> = listOf(
        Triple("builtin-reference","星枢 · GT与多行歌词",reference),
        Triple("builtin-gt","GT · 旅行仪表",gt),Triple("builtin-simple","简境 · Simple Drive",simple),Triple("builtin-neon","霓序 · Neon Stack",neon),
        Triple("builtin-tires","GT · 四轮胎压",gt.filterNot { it.id=="rpm" }+node("tires","tires",1445f,75f,430f,360f)),
        Triple("builtin-pedals","GT · 踏板与方向盘",gt.filterNot { it.id=="rpm" }+node("pedals","pedals",1445f,95f,430f,330f))
    ).map { (id,name,nodes) -> SceneXml.write(ThemeMetadata(id,name,1),ThemeScene(SceneDocument(nodes),SceneStyles())) }
}
