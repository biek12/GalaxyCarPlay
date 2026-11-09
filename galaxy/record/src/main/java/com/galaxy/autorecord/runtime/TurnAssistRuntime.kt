package com.galaxy.autorecord.runtime

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.*
import com.galaxy.autorecord.core.*

/** 闪烁保持与双闪互斥；缺失、过期、断线立即撤窗，不用缓存的灯光状态拉起相机。 */
internal class TurnAssistRuntime(private val context:Context,private val camera:SurfaceCameraSource) {
    private val handler=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(WindowManager::class.java)
    private var window:View?=null
    private var direction:String?=null
    private var candidate:String?=null
    private var candidateAt=0L
    private var leftAt=-10_000L
    private var rightAt=-10_000L
    @Volatile private var frameAt=0L
    @Volatile private var generation=0L
    private var shownAt=0L
    private var dismissed:String?=null
    private var feed:AutoServiceRecordFeed?=null
    private val tick=object:Runnable {override fun run(){update();handler.postDelayed(this,100)}}
    fun start(){
        if(feed!=null)return
        feed=AutoServiceRecordFeed(context,{PrefsSettingsStore(context).load()},onReady={feed?.authorize(true)},onLost={hide()},
            requestedKeys={listOf("leftTurn","rightTurn")},onChanged={update()}).also{it.foreground(true);it.connect()}
        handler.post(tick)
    }
    private fun update(){
        val snapshot=feed?.snapshot()?:return
        fun state(key:VehicleKey):Boolean? {val v=snapshot.value(key);if(v.status!=VehicleValueStatus.LIVE)return null
            return when(v.value.lowercase()){ "1","1.0","on","true","active"->true;"0","0.0","off","false","inactive"->false;else->null}}
        val left=state(VehicleKey.LEFT_TURN);val right=state(VehicleKey.RIGHT_TURN)
        if(left==null||right==null){candidate=null;leftAt=-10_000;rightAt=-10_000;dismissed=null;hide();return}
        val now=SystemClock.elapsedRealtime()
        if(direction!=null && (frameAt>0&&now-frameAt>1500 || frameAt==0L&&now-shownAt>8000)){
            dismissed=direction;hide()
        }
        if(left)leftAt=now;if(right)rightAt=now
        // 同一闪烁周期看到左右都亮即撤销；覆盖两键分包、左右采样轻微错开的双闪。
        val both=now-leftAt<1200 && now-rightAt<1200
        val next=when{both->null;now-leftAt<1200->"left";now-rightAt<1200->"right";else->null}
        if(next==null){candidate=null;if(!left&&!right&&now-maxOf(leftAt,rightAt)>=1200)dismissed=null;hide();return}
        if(next!=candidate){candidate=next;candidateAt=now;hide();return}
        if(next!=dismissed&&now-candidateAt>=300&&direction!=next)show(next)
    }
    private fun show(side:String){
        if(!android.provider.Settings.canDrawOverlays(context))return
        if(!camera.flatAvailable(side))return
        hide();direction=side;frameAt=0;shownAt=SystemClock.elapsedRealtime()
        val density=context.resources.displayMetrics.density
        fun dp(v:Int)=(v*density).toInt()
        val root=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK)}
        val bar=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(8),0)}
        bar.addView(TextView(context).apply{setTextColor(Color.WHITE);textSize=16f;text=(if(side=="left")"左转向"else"右转向")+" · 平面实时画面"},LinearLayout.LayoutParams(0,dp(42),1f))
        bar.addView(Button(context).apply {text="关闭";setOnClickListener{dismissed=side;hide()}},LinearLayout.LayoutParams(dp(70),dp(42)))
        root.addView(bar)
        val shownGeneration=++generation
        val surface=SurfaceView(context)
        surface.holder.setFixedSize(640,400)
        surface.holder.addCallback(object:SurfaceHolder.Callback {
            override fun surfaceCreated(holder:SurfaceHolder)=Unit
            override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){if(window===root)camera.bindAssist(side,holder.surface){if(generation==shownGeneration)frameAt=SystemClock.elapsedRealtime()}}
            override fun surfaceDestroyed(holder:SurfaceHolder){camera.bindAssist(side,null,holder.surface)}
        })
        root.addView(surface,LinearLayout.LayoutParams(-1,dp(240)))
        val params=WindowManager.LayoutParams(dp(384),dp(282),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,android.graphics.PixelFormat.OPAQUE).apply {
            gravity=Gravity.CENTER_VERTICAL or if(side=="left")Gravity.LEFT else Gravity.RIGHT
        }
        window=root
        runCatching{manager.addView(root,params)}.onFailure{window=null;direction=null;RecordDiagnostics.event("turn_overlay",it)}
    }
    private fun hide(){generation++;val old=direction;direction=null;old?.let{camera.bindAssist(it,null)}
        window?.let{runCatching{manager.removeViewImmediate(it)}};window=null}
    fun release(){handler.removeCallbacks(tick);feed?.release();feed=null;hide()}
}
