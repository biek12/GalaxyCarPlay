package com.ecarx.autoservice.web

import android.content.Context
import android.os.*
import com.ecarx.autoservice.remote.RemoteHttp
import com.ecarx.autoservice.remote.RemoteSettings
import org.json.JSONObject
import java.io.Closeable

/** 车辆账号独立于12/24小时诊断窗口；一个串行worker拥有网络和绑定缓存，关闭使旧回调失效。 */
internal class VehicleWebBridge(private val app: Context): Closeable {
    private val prefs=app.getSharedPreferences("vehicle-web",0)
    private val settings=RemoteSettings(app)
    private val thread=HandlerThread("AutoService-vehicle-web").apply{start()}
    private val io=Handler(thread.looper)
    private val http=RemoteHttp(app,settings)
    private val mediaHttp=RemoteHttp(app,settings)
    private val mediaThread=HandlerThread("AutoService-web-media").apply{start()}
    private val mediaIo=Handler(mediaThread.looper)
    private val ranges=RangeMediaBridge(app,settings)
    @Volatile private var closed=false
    private val uploads=java.util.Collections.synchronizedSet(mutableSetOf<ParcelFileDescriptor>())
    private var registered=false
    private var poll=JSONObject()
    private var record=JSONObject()
    private var recordAt=0L
    private var failure=""
    private var delay=10000L
    private var recordLaunchAt=-60000L
    private val tick=object:Runnable {override fun run(){
        if(closed)return
        if(prefs.getBoolean("enabled",false))runCatching{sync();failure="";delay=10000L}
            .onFailure{failure="车辆服务暂时无法连接，请检查网络后重试";delay=(delay*2).coerceAtMost(60000)}
        if(!closed)io.postDelayed(this,delay)
    }}
    fun start(){io.post(tick)}
    private fun json(path:String,value:JSONObject=JSONObject()):JSONObject {
        check(!closed)
        return http.json("/device/v1/$path",value).also{check(!closed)}
    }
    private fun register(){if(!registered){json("register");registered=true}}
    private fun basicInfo()=JSONObject().put("model",app.getSharedPreferences("platform",0).getString("model","L6"))
        .put("modelSource","车端适配配置").put("manufacturer",Build.MANUFACTURER).put("deviceModel",Build.MODEL)
        .put("androidVersion",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT)
        .put("serviceVersion",app.packageManager.getPackageInfo(app.packageName,0).versionName)
    private fun snapshot():JSONObject {
        val model=basicInfo().optString("model")
        val value=JSONObject(record.toString()).put("name","银河 $model").put("model","银河 $model")
            .put("basicInfo",basicInfo()).put("presence",JSONObject().put("state","online"))
        if(SystemClock.elapsedRealtime()-recordAt>45000){value.remove("capabilities");value.remove("sentry");value.remove("storage");value.remove("liveSample");value.put("recording",JSONObject().put("state","unknown").put("observedAt",System.currentTimeMillis()))}
        return value
    }
    private fun sync(){
        register()
        if(SystemClock.elapsedRealtime()-recordLaunchAt>=60000){
            recordLaunchAt=SystemClock.elapsedRealtime()
            runCatching{app.startForegroundService(android.content.Intent().setComponent(android.content.ComponentName("com.galaxy.autorecord","com.galaxy.autorecord.web.RecordWebService")))}
        }
        json("snapshot",JSONObject().put("snapshot",snapshot()))
        poll=json("poll")
        val state=json("binding-state")
        check(prefs.edit().putString("state",state.toString()).commit())
        if(state.optBoolean("bound"))prefs.edit().remove("code").apply()
    }
    private fun localState():JSONObject {
        val state=runCatching{JSONObject(prefs.getString("state","{}")!!)}.getOrDefault(JSONObject())
        return state.put("enabled",prefs.getBoolean("enabled",false)).put("basicInfo",basicInfo())
            .put("error",failure).put("bindings",poll.optJSONArray("bindings")?:org.json.JSONArray())
            .put("code",prefs.getString("code","")).put("expiresAt",prefs.getLong("expiresAt",0))
            .put("scanUrl",prefs.getString("code","")?.takeIf{it.isNotBlank()}?.let{settings.origin+"/app/vehicles#bind="+android.net.Uri.encode(it)}?:"")
    }
    fun request(action:String,payload:Bundle,reply:(Bundle)->Unit){io.post{
        if(closed)return@post
        val response=Bundle()
        try {
            when(action){
                "disable"->{check(prefs.edit().putBoolean("enabled",false).commit());registered=false;poll=JSONObject()}
                "state"->Unit
                "enable","code"->{
                    check(prefs.edit().putBoolean("enabled",true).commit());sync()
                    if(action=="code"&&!localState().optBoolean("bound")){
                        // 两App共用同一个尚未过期的码，刷新才会重新签发。
                        if(prefs.getLong("expiresAt",0)<=System.currentTimeMillis()||payload.getBoolean("refresh")){
                            val code=json("binding-codes")
                            check(prefs.edit().putString("code",code.getString("code")).putLong("expiresAt",code.getLong("expiresAt")).commit())
                        }
                    }
                }
                "confirm"->{register();json("binding-confirmations",JSONObject().put("bindingId",payload.getString("bindingId")).put("accepted",payload.getBoolean("accepted")));sync()}
                "unbind"->{register();json("unbind");prefs.edit().remove("code").remove("expiresAt").apply();sync()}
                "record-publish"->{
                    check(prefs.getBoolean("enabled",false)){"请先启用车辆在线服务"}
                    val value=JSONObject(payload.getString("json")?:"{}")
                    value.optJSONObject("snapshot")?.let{record=it;recordAt=SystemClock.elapsedRealtime()}
                    register()
                    value.optJSONArray("clips")?.let{json("index",JSONObject().put("clips",it))}
                    sync()
                }
                "record-poll"->{check(prefs.getBoolean("enabled",false)){"vehicle_web_disabled"};register();poll=json("poll");response.putString("json",poll.toString())}
                "record-signals"->{check(prefs.getBoolean("enabled",false));register();json("live-signals",JSONObject(payload.getString("json")?:"{}"))}
                "record-live-status"->{check(prefs.getBoolean("enabled",false));register();json("live-status",JSONObject(payload.getString("json")?:"{}"))}
                "record-telemetry"->{check(prefs.getBoolean("enabled",false));register();json("telemetry",JSONObject(payload.getString("json")?:"{}"))}
                "record-result"->{check(prefs.getBoolean("enabled",false));register();json("job-results",JSONObject(payload.getString("json")?:"{}"))}
            }
            failure=""
            if(!response.containsKey("json"))response.putString("json",if(action.startsWith("record-"))"{}" else localState().toString())
        }catch(error:Exception){
            // URL、secret和一次性材料均不进入异常上报。
            failure="车辆服务暂不可用，请检查网络或 AutoService 自动运行状态"
            response.putString("error",failure)
            response.putString("json",localState().toString())
        }
        if(!closed)reply(response)
    }}
    fun upload(clipId:String,direction:String,fd:ParcelFileDescriptor,length:Long,reply:(Bundle)->Unit){
        if(closed){fd.close();return}
        uploads.add(fd)
        mediaIo.post{
            val response=Bundle()
            try{ParcelFileDescriptor.AutoCloseInputStream(fd).use{input->
                check(!closed&&prefs.getBoolean("enabled",false));register()
                if(direction.startsWith("open-")){
                    ranges.register(clipId,direction.removePrefix("open-"),ParcelFileDescriptor.dup(fd.fileDescriptor),length)
                }else if(direction=="index"){require(length<=4*1024*1024);mediaHttp.upload("/device/v1/index",input,length,"POST","application/json")}
                else mediaHttp.upload("/device/v1/media/$clipId/$direction",input,length)
            };response.putBoolean("accepted",true)}catch(error:Exception){runCatching{fd.close()};response.putString("error","媒体传输失败，请重试")}
            uploads.remove(fd)
            if(!closed)reply(response)
        }
    }
    override fun close(){closed=true;ranges.close();synchronized(uploads){uploads.toList().forEach{runCatching{it.close()}};uploads.clear()};io.removeCallbacksAndMessages(null);http.close();mediaHttp.close();mediaIo.removeCallbacksAndMessages(null);mediaThread.quitSafely();thread.quitSafely()}
}
