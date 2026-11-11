package com.ecarx.autoservice

import android.content.Context
import android.os.*
import com.ecarx.autoservice.platform.RootServiceClient
import com.ecarx.autoservice.platform.QnxCredentials
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import org.json.JSONObject

/** 主线程持有意图，独立worker负责部署；续租写入使用另一worker，不被QNX登录阻塞。 */
internal class ClusterHookController(private val context:Context,private val changed:()->Unit,
    private val request:(Int,JSONObject,Int)->JSONObject={operation,payload,timeout->RootServiceClient.request(context,operation,payload,timeout)}):AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val worker=SerialWorker("AutoService-cluster-hook")
    private var wanted=false
    private var root=false
    private var generation=0L
    private var source=0
    private var expected=0
    private var configuration:ClusterDisplayConfiguration?=null
    private var ticket=0L
    private var verified=false
    private var busy=false
    private var closed=false
    private var attempts=0
    private var nextAttempt=0L
    private var receiptMissingSince=0L
    private var state=Bundle().apply{putString("state","idle")}
    fun snapshot()=Bundle(state)
    private fun report(value:Bundle){if(state.toString()!=value.toString()){state=value;main.post{if(!closed)changed()}}}
    private fun phase(value:String)=report(Bundle().apply{putString("state",value)})
    private fun control(action:String)=QnxCredentials.loadOptional(context).put("action",action)
    fun update(active:Boolean,ready:Boolean,config:ClusterDisplayConfiguration?) {
        val nextSource=when(config?.background){"image"->1;"carplay-map"->2;"local-map"->3;else->0}
        val intentChanged=wanted!=active || source!=nextSource || root!=ready || configuration!=config
        if(source!=nextSource || configuration?.image!=config?.image)verified=false
        if(intentChanged){++ticket;attempts=0;nextAttempt=0;receiptMissingSince=0;if(active)generation=0}
        if(!ready)verified=false
        wanted=active;root=ready;source=nextSource;configuration=config
        if(!active&&state.getString("state")=="idle")return
        if(active&&!ready)phase("waiting-root")
        else if(active&&intentChanged)phase("preparing")
        else if(!active&&state.getString("state") !in setOf("idle","restored"))phase("restoring")
        if(intentChanged&&!busy)poll()
        main.removeCallbacks(tick);if(active||state.getString("state")!="restored")main.postDelayed(tick,1000)
    }
    // Only a successfully published lease may start the native process.
    fun published(value:Long,flags:Int) {
        if(generation!=value){generation=value;++ticket;attempts=0;receiptMissingSince=0;nextAttempt=SystemClock.elapsedRealtime()+3500}
        expected=flags
        if(!busy)poll()
    }
    private val tick=object:Runnable{override fun run(){if(closed)return;poll();if(wanted||state.getString("state")!="restored")main.postDelayed(this,1000)}}
    private fun poll() {
        if(closed||busy||!root)return
        if(wanted&&generation==0L)return
        if(!wanted&&state.getString("state") in setOf("idle","restored"))return
        val current=ticket;val active=wanted;val lease=generation;val type=source;val flags=expected
        val image=configuration?.takeIf{it.background==ClusterDisplayConfiguration.IMAGE_BACKGROUND}?.image.orEmpty()
        val currentVerified=verified
        val missingSince=receiptMissingSince
        val canStart=SystemClock.elapsedRealtime()>=nextAttempt && attempts<3
        busy=true
        worker.post {
            var attempted=false
            var ensured=false
            var receiptReceived=false
            var missingAt=0L
            val result=runCatching {
                if(!active) {
                    val response=request(11,control("stop"),40000)
                    check(response.optString("state")=="restored"){"cluster_restore_unconfirmed"}
                    Bundle().apply{putString("state","restored")}
                } else {
                    val reply=if(currentVerified)request(12,JSONObject(),5000)else JSONObject()
                    val bytes=android.util.Base64.decode(reply.optString("receipt"),android.util.Base64.NO_WRAP)
                    val receipt=if(currentVerified)ClusterHookReceipt.decode(bytes,lease,type,flags,System.currentTimeMillis())else null
                    receiptReceived=receipt!=null
                    receipt ?: run {
                        if(currentVerified){
                            val now=SystemClock.elapsedRealtime()
                            missingAt=if(missingSince==0L)now else missingSince
                            if(now-missingAt<6000)return@run Bundle().apply{putString("state","waiting-feedback")}
                        }
                        if(!canStart) return@run Bundle().apply{putString("state",if(attempts>=3)"failed" else "preparing")}
                        attempted=true
                        val started=request(11,control("ensure").put("generation",lease).put("image",image),45000)
                        check(started.optString("state")=="running"){
                            started.optString("stage").takeIf{it.matches(Regex("[a-z_]+"))}?:"cluster_start_unconfirmed"
                        }
                        ensured=true
                        Bundle().apply{putString("state","preparing")}
                    }
                }
            }
            main.post {
                busy=false
                if(closed)return@post
                if(current!=ticket){poll();return@post}
                receiptMissingSince=if(receiptReceived||ensured)0 else missingAt
                if(ensured)verified=true
                if(attempted){++attempts;nextAttempt=SystemClock.elapsedRealtime()+15000}
                result.onSuccess{report(it)}.onFailure{ServiceTrace.event("cluster_hook_lifecycle",it);phase(if(active)"failed" else "restoring")}
            }
        }
    }
    override fun close(){closed=true;++ticket;main.removeCallbacks(tick);worker.post{runCatching{request(11,control("stop"),40000)}};worker.close()}
}
