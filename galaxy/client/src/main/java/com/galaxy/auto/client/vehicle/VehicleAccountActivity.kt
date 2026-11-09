package com.galaxy.auto.client.vehicle

import android.app.*
import android.os.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.client.R
import com.galaxy.auto.contract.AutoContract
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

/** 两App使用同一界面与AutoService身份；页面只借Binder，不存设备凭据或账号所有权。 */
class VehicleAccountActivity:Activity() {
    override fun attachBaseContext(base:android.content.Context){
        val config=android.content.res.Configuration(base.resources.configuration).apply{densityDpi=280}
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    private lateinit var client:AutoClient
    private lateinit var body:LinearLayout
    private lateinit var hint:TextView
    private val main=Handler(Looper.getMainLooper())
    private var connected=false
    private var busy=false
    private var signature=""
    private var value=JSONObject()
    private val tick=object:Runnable{override fun run(){if(connected&&!busy)request("state");main.postDelayed(this,2000)}}
    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt()
    private fun color(id:Int)=getColor(id)
    private val fg get()=color(R.color.vehicle_account_text)
    private val muted get()=color(R.color.vehicle_account_muted)
    private fun round(c:Int)=GradientDrawable().apply{setColor(c);cornerRadius=dp(8).toFloat()}
    private fun column(pad:Int=20)=LinearLayout(this).apply{orientation=1;setPadding(dp(pad),dp(pad),dp(pad),dp(pad))}
    private fun text(v:String,size:Float=18f,secondary:Boolean=false)=TextView(this).apply{
        text=v;textSize=size;setTextColor(if(secondary)muted else fg);setPadding(0,dp(5),0,dp(7));includeFontPadding=false
    }
    private fun button(title:String,primary:Boolean=false,action:()->Unit)=Button(this).apply{
        text=title;isAllCaps=false;textSize=17f;minHeight=dp(56);stateListAnimator=null
        backgroundTintList=null;background=round(color(if(primary)R.color.vehicle_account_accent else R.color.vehicle_account_soft))
        setTextColor(if(primary)Color.WHITE else fg);setOnClickListener{if(!busy)action()}
    }
    private fun card():LinearLayout=column(24).apply{background=round(color(R.color.vehicle_account_surface))}.also{
        body.addView(it,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(16)})
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        actionBar?.hide()
        val root=column(0).apply{setBackgroundColor(color(R.color.vehicle_account_bg))}
        val heading=column(28)
        heading.addView(button("返回"){finish()},LinearLayout.LayoutParams(dp(100),dp(56)))
        heading.addView(text("车辆与账号",30f));heading.addView(text("AutoService · AutoRecord 共享",15f,true))
        root.addView(heading)
        body=column(24);root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        hint=text("正在连接 AutoService…",15f,true);root.addView(hint,LinearLayout.LayoutParams(-1,-2).apply{setMargins(dp(28),0,dp(28),dp(16))})
        setContentView(root)
        client=AutoClient(this,AutoContract.OBSERVER,onReady={connected=true;request("state")},onState={
            if(it.startsWith("ipc:")||it.startsWith("connect:")||it=="service_disconnected"){connected=false;hint.text="AutoService 未连接，请开启自动运行后重试"}
        })
        client.connect()
    }
    override fun onStart(){super.onStart();main.post(tick)}
    override fun onStop(){main.removeCallbacks(tick);super.onStop()}
    override fun onDestroy(){client.close();super.onDestroy()}
    private fun request(action:String,payload:Bundle=Bundle()){
        if(busy)return
        busy=true
        if(action!="state")hint.text="正在处理…"
        client.vehicleWebRequest(action,payload){reply->
            busy=false
            val error=reply.getString("error")
            reply.getString("json")?.let{runCatching{JSONObject(it)}.getOrNull()?.let{next->value=next;render()}}
            hint.text=error?:value.optString("error").ifBlank{if(value.optBoolean("enabled"))"绑定长期有效，直到主动取消；临时码仅用于首次添加" else "启用后车辆通过自身网络连接，不依赖远程调试授权时长"}
        }
    }
    private fun render(){
        val code=value.optString("code")
        val until=value.optLong("expiresAt")
        val seconds=((until-System.currentTimeMillis())/1000).coerceAtLeast(0)
        val key=value.toString()+":"+(seconds==0L)
        if(signature==key)return
        signature=key;body.removeAllViews()
        val info=value.optJSONObject("basicInfo")?:JSONObject()
        card().apply{
            addView(text("我的车辆",14f,true));addView(text("银河 ${info.optString("model")}",26f))
            addView(text("Android ${info.optString("androidVersion")} · AutoService ${info.optString("serviceVersion")}",15f,true))
            addView(text("绑定后可在网页查看实时画面与历史录像",15f,true))
        }
        if(value.optBoolean("bound")){
            card().apply{
                addView(text("已绑定 · 长期有效",23f).apply{setTextColor(color(R.color.vehicle_account_success))})
                val owner=value.optJSONObject("owner")?:JSONObject()
                addView(text("${owner.optString("displayName")}  ${owner.optString("phoneMasked")}",18f))
                addView(text("重启、更新 App 或调试窗口到期均保留绑定。",15f,true))
                addView(button("取消绑定"){
                    AlertDialog.Builder(this@VehicleAccountActivity).setTitle("取消车辆绑定？").setMessage("当前账号及其观看授权将立即失效，录像仍保留在车端。")
                        .setNegativeButton("保留绑定",null).setPositiveButton("取消绑定"){_,_->request("unbind")}.show()
                })
            }
        }else{
            card().apply{
                addView(text(if(seconds>0)"扫码添加车辆" else "添加到我的账号",23f))
                addView(text("用手机相机扫描二维码，或在网页输入绑定码；随后在车端确认目标账号。",16f,true))
                if(code.isNotBlank()&&seconds>0){
                    val matrix=QRCodeWriter().encode(value.getString("scanUrl"),BarcodeFormat.QR_CODE,512,512)
                    val bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
                    val pixels=IntArray(512*512){i->if(matrix[i%512,i/512])Color.BLACK else Color.WHITE};bitmap.setPixels(pixels,0,512,0,0,512,512)
                    addView(ImageView(this@VehicleAccountActivity).apply{setImageBitmap(bitmap);contentDescription="车辆添加二维码"},LinearLayout.LayoutParams(dp(240),dp(240)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(16);bottomMargin=dp(16)})
                    addView(text(code,24f).apply{gravity=Gravity.CENTER;typeface=Typeface.MONOSPACE;setTextIsSelectable(true)})
                    addView(text("有效至 ${java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.CHINA).format(java.util.Date(until))} · 一次性使用",14f,true).apply{gravity=Gravity.CENTER})
                }
                addView(button(if(seconds>0)"重新申请绑定码" else "申请绑定码与二维码",true){request("code",Bundle().apply{putBoolean("refresh",true)})})
            }
        }
        val bindings=value.optJSONArray("bindings")
        for(i in 0 until (bindings?.length()?:0)){
            val pending=bindings!!.getJSONObject(i)
            if(pending.optLong("expiresAt")<=System.currentTimeMillis())continue
            card().apply{
                addView(text("确认绑定账号",23f));addView(text("${pending.optString("displayName")}\n${pending.optString("phoneMasked")}",20f))
                addView(text("确认后该账号可访问此车的录像与在线功能。",15f,true))
                fun confirm(accept:Boolean)=request("confirm",Bundle().apply{putString("bindingId",pending.getString("bindingId"));putBoolean("accepted",accept)})
                addView(button("确认是我的账号",true){confirm(true)})
                addView(button("拒绝此请求"){confirm(false)})
            }
        }
        card().addView(button(if(value.optBoolean("enabled"))"暂停车辆在线服务" else "启用车辆在线服务"){
            request(if(value.optBoolean("enabled"))"disable" else "enable")
        })
    }
}
