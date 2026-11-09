package com.galaxy.autorecord.runtime

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import com.galaxy.autorecord.BuildConfig
import com.galaxy.autorecord.core.*
import com.galaxy.autorecord.ui.RecordIcons

/** 低亮度全屏监控提示；显示状态与录制事实分开，不修改原厂HMI或电源。 */
class SentinelActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) {
        applyOverrideConfiguration(com.galaxy.autorecord.ui.RecordUiConfiguration.from(base))
        super.attachBaseContext(base)
    }
    private lateinit var hub: RecordSessionHub
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var optic: SentinelOptic
    private val observer: (Boolean) -> Unit = { render() }
    private var preview = false
    private val white = Color.rgb(241,244,247)
    private val muted = Color.rgb(139,148,158)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int = white) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        includeFontPadding = false
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        hub = RecordSessionHub.get(this)
        // 现场UI检查只读预览，不保存布防意图、不启动相机或调用VSTD。
        preview = BuildConfig.DEBUG && intent.getBooleanExtra("preview", false)
        if (!preview && !PrefsSettingsStore(this).load().sentinelEnabled) { finish(); return }
        if (!preview) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.statusBarColor = Color.rgb(7,10,14)
        window.navigationBarColor = Color.rgb(7,10,14)
        window.decorView.systemUiVisibility = 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(7,10,14)); setPadding(dp(28),dp(22),dp(28),dp(26))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageButton(this).apply {
            setImageDrawable(RecordIcons.drawable(this@SentinelActivity,"back",white))
            background = null; contentDescription = "返回"; setOnClickListener { finish() }
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(label("AutoRecord",16f,muted),LinearLayout.LayoutParams(0,dp(48),1f))
        header.addView(label(if(preview) "界面预览" else "哨兵模式",13f,muted),LinearLayout.LayoutParams(dp(80),dp(48)))
        root.addView(header,LinearLayout.LayoutParams(-1,dp(48)))
        val stage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        stage.addView(label("S E N T R Y",12f,muted),LinearLayout.LayoutParams(-1,dp(30)))
        title = label("哨兵模式",36f).apply { typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL) }
        stage.addView(title,LinearLayout.LayoutParams(-1,dp(62)))
        optic = SentinelOptic(this)
        stage.addView(optic,LinearLayout.LayoutParams(-1,dp(230)))
        status = label("",23f).apply { typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL) }
        stage.addView(status,LinearLayout.LayoutParams(-1,dp(46)))
        detail = label("",15f,muted).apply { setLineSpacing(dp(6).toFloat(),1f) }
        stage.addView(detail,LinearLayout.LayoutParams(-1,dp(65)))
        root.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(label("持续守护，直至手动关闭",15f,white),LinearLayout.LayoutParams(-1,dp(32)))
        root.addView(label("电量低于 20% 自动退出",13f,muted),LinearLayout.LayoutParams(-1,dp(30)))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0,dp(20),0,dp(12)) }
        fun action(text: String, primary: Boolean, click: () -> Unit) = label(text,16f,if(primary)Color.rgb(13,28,20)else white).apply {
            gravity = Gravity.CENTER; isClickable = true; isFocusable = true
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if(primary) Color.rgb(124,220,165) else Color.rgb(27,33,41)) }
            setOnClickListener { click() }
        }
        actions.addView(action("查看画面",true) {
            startActivity(Intent(this,com.galaxy.autorecord.ui.RecordActivity::class.java)); finish()
        },LinearLayout.LayoutParams(0,dp(52),1f).apply { marginEnd = dp(12) })
        actions.addView(action(if(preview)"退出预览" else "关闭哨兵",false) {
            if(!preview) {
                val store = PrefsSettingsStore(this); store.save(store.load().copy(sentinelEnabled=false)); hub.settingsChanged()
            }
            finish()
        },LinearLayout.LayoutParams(0,dp(52),1f))
        root.addView(actions,LinearLayout.LayoutParams(-1,-2))
        setContentView(root); hub.observe(observer); render()
    }
    private fun render() {
        val value = hub.sentinelState
        val recording = !preview && hub.snapshot.state == RecordingState.RECORDING && value.getBoolean("capture")
        val alert = recording && value.getString("mode") in setOf("alert","alarm")
        val color = if(alert) Color.rgb(248,113,113) else if(recording) Color.rgb(124,220,165) else Color.rgb(174,186,202)
        title.text = if(recording) "正在录像" else "哨兵模式"
        status.text = if(preview) "界面预览 · 未布防" else if(recording) "车辆周围画面正在记录" else sentinelStateLabel(value.getString("state"))
        status.setTextColor(color); optic.tint = color
        detail.text = when {
            preview -> "仅检查显示效果\n不会开启监控或改变车辆状态"
            recording -> "事件片段将保留\n请勿触碰车辆"
            else -> "满足驻车与锁车条件后开始监控\n当前未进入哨兵录像"
        }
    }
    override fun onDestroy() { if(::hub.isInitialized) hub.removeObserver(observer); super.onDestroy() }
}

/** 静态光学镜头装饰，无定时动画/轮询；不表示人体识别或实际摄像头数量。 */
private class SentinelOptic(context: android.content.Context): View(context) {
    var tint: Int = Color.LTGRAY
        set(value) { field=value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lens = context.getDrawable(com.galaxy.autorecord.R.drawable.record_icon_live)!!.mutate()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x=width*.5f; val y=height*.5f; val r=minOf(width*.32f,height*.38f)
        paint.shader=RadialGradient(x,y,r*1.3f,intArrayOf(Color.argb(32,Color.red(tint),Color.green(tint),Color.blue(tint)),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        paint.style=Paint.Style.FILL; canvas.drawCircle(x,y,r*1.3f,paint); paint.shader=null
        paint.style=Paint.Style.STROKE
        for ((scale,alpha) in listOf(1.18f to 28,1f to 75,.78f to 110)) {
            paint.color=Color.argb(alpha,Color.red(tint),Color.green(tint),Color.blue(tint)); paint.strokeWidth=resources.displayMetrics.density
            canvas.drawCircle(x,y,r*scale,paint)
        }
        paint.color=tint; paint.strokeWidth=2*resources.displayMetrics.density
        val ring=RectF(x-r,y-r,x+r,y+r)
        canvas.drawArc(ring,218f,104f,false,paint); canvas.drawArc(ring,38f,104f,false,paint)
        lens.setTint(tint); val s=(r*.38f).toInt(); lens.setBounds(x.toInt()-s,y.toInt()-s,x.toInt()+s,y.toInt()+s); lens.draw(canvas)
    }
}

fun sentinelStateLabel(state:String?):String=when(state){
    "monitoring"->"正在准备监控录像";"waiting_lock"->"等待锁车";"waiting_park"->"等待驻车P挡"
    "battery_unknown"->"等待有效电量";"low_battery"->"电量低于20%，已退出"
    "suspended"->"车辆休眠，等待原车电源恢复";"power_unknown"->"等待电源状态"
    "lock_unknown"->"等待锁车状态";"waiting_mode"->"等待VSTD状态回读"
    "oem_active"->"原厂哨兵已启用，请先关闭原厂哨兵"
    "platform_disabled"->"AutoService已关闭";"service_update_required"->"请更新AutoService"
    "prepare_failed"->"哨兵准备失败";"service_data_stale"->"AutoService状态已过期"
    "disabled"->"已关闭";else->"正在准备"
}
