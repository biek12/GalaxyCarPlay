package com.ecarx.autoservice.sentinel

import android.content.Context
import android.net.LocalSocket
import android.os.*
import com.ecarx.autoservice.platform.RootServiceClient
import com.galaxy.auto.contract.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

/** AutoService持有持久布防意图、电源事件和专用VSTD写口；App只消费运行状态。 */
internal class SentinelCoordinator(private val context:Context,private val ensureRoot:()->Unit,
    private val changed:()->Unit,private val demandChanged:()->Unit):java.io.Closeable {
    private val main=Handler(Looper.getMainLooper())
    private val executor=java.util.concurrent.Executors.newSingleThreadExecutor{Thread(it,"sentinel-control")}
    private val prefs=context.getSharedPreferences("sentinel",0)
    @Volatile private var enabled=prefs.getBoolean("enabled",false)
    @Volatile private var platform=false
    private var values=Bundle()
    private var power=0
    private var powerSequence=0L
    @Volatile private var generation=0L
    private var stream:LocalSocket?=null
    private var busy=false
    private var armSent=false
    private var error=""
    private var retryAt=0L
    private var failures=0
    private var reason="disabled"
    private var alertSequence=0L
    private var alertAt=0L
    private val epoch=java.util.UUID.randomUUID().toString()
    private var previousMode:String?=null
    private var lastPublished=""
    private var lastPublishedAt=0L
    @Volatile private var closed=false
    val demands:Map<String,Int> get()=if(enabled&&platform)KEYS.associateWith{500}else emptyMap()
    private val tick=object:Runnable{override fun run(){evaluate();if(!closed&&enabled&&platform)main.postDelayed(this,500)}}
    fun configure(next:Boolean){
        check(prefs.edit().putBoolean("enabled",next).commit());enabled=next
        if(next){armSent=false;error=""}else {disarm();stopStream()}
        refresh();demandChanged()
    }
    fun platform(active:Boolean){
        if(platform==active)return
        platform=active
        if(!active){disarm();stopStream();values=Bundle();power=0}
        if(active&&!enabled&&prefs.getBoolean("ownsMode",false))disarm()
        refresh();demandChanged()
    }
    fun accept(next:Bundle){values=Bundle(next);evaluate()}
    private fun signal(key:String):String?=values.getBundle(key)?.let(VehicleSignal::parse)?.takeIf{
        it.effectiveStatus(SystemClock.elapsedRealtime())==SignalStatus.VALID}?.value
    private fun refresh(){main.removeCallbacks(tick);evaluate();if(enabled&&platform)main.postDelayed(tick,500)}
    private fun evaluate(){
        if(closed)return
        val now=SystemClock.elapsedRealtime()
        if(now>=retryAt&&error.isNotEmpty()&&error!="oem_sentinel_already_active"){
            if(enabled&&platform){error="";retryAt=now+60_000}
        }
        val mode=signal("sentinelMode")
        val soc=signal("soc")?.toDoubleOrNull()
        reason=SentinelPolicy.reason(SentinelPolicy.Input(enabled,platform,soc,signal("centralLock")?.toDoubleOrNull(),signal("gear"),mode,power))
        if(reason=="low_battery"){
            // 硬退出本次布防，电量恢复也不由旧任务重新开启；用户可重新开启。
            enabled=false;check(prefs.edit().putBoolean("enabled",false).commit());disarm();stopStream();demandChanged()
        }
        if(enabled&&platform&&stream==null&&!busy&&error.isEmpty())startStream()
        if(enabled&&platform&&soc!=null&&soc>=20&&mode!=null&&!armSent&&!busy&&error.isEmpty()){
            if(mode=="off"||prefs.getBoolean("ownsMode",false))arm(mode!="off")
            else {error="oem_sentinel_already_active";reason="oem_active"}
        }
        if(mode in setOf("alert","alarm")&&mode!=previousMode){alertSequence++;alertAt=values.getBundle("sentinelMode")?.let(VehicleSignal::parse)?.receivedAt?:SystemClock.elapsedRealtime()}
        previousMode=mode
        val key="$enabled:$reason:$power:$alertSequence:$error"
        if(key!=lastPublished||enabled&&platform&&SystemClock.elapsedRealtime()-lastPublishedAt>=1000){lastPublished=key;lastPublishedAt=SystemClock.elapsedRealtime();changed()}
    }
    private fun arm(resume:Boolean){
        busy=true;val ticket=generation;val requestAt=SystemClock.elapsedRealtimeNanos()
        // 在途写也登记为本应用的意图，关闭/崩溃恢复必须能撤销。
        check(prefs.edit().putBoolean("ownsMode",true).commit())
        executor.execute{
            val outcome=runCatching{ensureRoot();check(!closed&&ticket==generation&&enabled&&platform){"sentinel_cancelled"};RootServiceClient.request(context,9,JSONObject().put("action",if(resume)"resume"else"arm").put("requestAtNanos",requestAt))}
            main.post{
                busy=false
                if(ticket!=generation||!enabled||!platform){disarm();return@post}
                val reply=outcome.getOrNull()
                if(reply?.optBoolean("accepted")==true){armSent=true;error="";failures=0}
                else error=reply?.optString("stage").orEmpty().ifEmpty{outcome.exceptionOrNull()?.javaClass?.simpleName?:"sentinel_arm_failed"}
                if(error.isNotEmpty())retryAt=SystemClock.elapsedRealtime()+minOf(60_000L,15_000L*(++failures))
                evaluate()
            }
        }
    }
    private fun disarm(){
        if(!prefs.getBoolean("ownsMode",false))return
        val requestAt=SystemClock.elapsedRealtimeNanos()
        executor.execute{
            val result=runCatching{RootServiceClient.request(context,9,JSONObject().put("action","disarm").put("requestAtNanos",requestAt))}
            if(result.getOrNull()?.optBoolean("accepted")==true)prefs.edit().putBoolean("ownsMode",false).commit()
            else main.post{error="sentinel_disarm_pending";changed()}
        }
        armSent=false
    }
    private fun startStream(){
        busy=true;val ticket=++generation
        Thread({
            var socket:LocalSocket?=null
            try{
                ensureRoot();check(!closed&&ticket==generation&&enabled&&platform){"sentinel_cancelled"};socket=RootServiceClient.connect(context,40_000)
                val connected=socket
                main.post{if(!closed&&ticket==generation&&enabled&&platform){stream=connected;busy=false;evaluate()}else connected.close()}
                DataOutputStream(socket.outputStream).apply{writeInt(10);flush()}
                val input=DataInputStream(socket.inputStream)
                while(!closed){
                    val event=com.ecarx.autoservice.platform.RootWire.read(input)
                    check(!event.has("error")){"sentinel_power_stream_failed"}
                    main.post{if(ticket==generation&&enabled&&platform){power=event.optInt("power");powerSequence=event.optLong("powerSequence");evaluate()}}
                }
            }catch(e:Exception){main.post{if(ticket==generation&&!closed){stream=null;busy=false;power=0;error="power_link_${e.javaClass.simpleName}";retryAt=SystemClock.elapsedRealtime()+minOf(60_000L,15_000L*(++failures));evaluate()}}}
            finally{runCatching{socket?.close()}}
        },"sentinel-power-link").start()
    }
    private fun stopStream(){generation++;runCatching{stream?.close()};stream=null;busy=false}
    fun idle(sequence:Long){
        if(sequence<=0||sequence!=powerSequence||power!=7)return
        executor.execute{runCatching{RootServiceClient.request(context,9,JSONObject().put("action","powerReady").put("powerSequence",sequence))}
            .onFailure{com.ecarx.autoservice.ServiceTrace.event("sentinel_power_completion",it)}}
    }
    fun snapshot()=Bundle().apply{
        val displayState=when{reason=="low_battery"->reason;error=="oem_sentinel_already_active"->"oem_active";error.isNotEmpty()->"prepare_failed";else->reason}
        putInt("version",1);putBoolean("enabled",enabled);putString("state",displayState);putInt("power",power);putLong("powerSequence",powerSequence)
        putBoolean("capture",reason=="monitoring"&&error.isEmpty()&&armSent)
        putString("epoch",epoch);putLong("alertAt",alertAt);putLong("alertSequence",alertSequence);putString("mode",signal("sentinelMode"));putString("error",error)
        putInt("minimumSoc",20);putLong("at",SystemClock.elapsedRealtime())
    }
    /** 全局关闭在释放helper前做一次有时限的本App撤销，不重新准备Root。 */
    fun preparePlatformStop(){
        platform=false;stopStream();armSent=false
        if(!prefs.getBoolean("ownsMode",false))return
        val result=runCatching{RootServiceClient.request(context,9,JSONObject().put("action","disarm")
            .put("requestAtNanos",SystemClock.elapsedRealtimeNanos()),2000)}
        if(result.getOrNull()?.optBoolean("accepted")==true)prefs.edit().putBoolean("ownsMode",false).commit()
        else error="sentinel_disarm_pending"
    }
    override fun close(){if(closed)return;preparePlatformStop();closed=true;main.removeCallbacks(tick);stopStream();executor.shutdown()}
    companion object {val KEYS=setOf("sentinelMode","centralLock","gear","soc")}
}
