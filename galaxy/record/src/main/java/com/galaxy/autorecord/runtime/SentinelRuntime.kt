package com.galaxy.autorecord.runtime

import android.content.Context
import android.content.Intent
import android.os.*
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.galaxy.autorecord.core.*

/** 消费AutoService哨兵状态；只拥有因哨兵创建的录像，不结束已有手动录像。 */
internal class SentinelRuntime(private val context:Context,private val hub:RecordSessionHub){
    private val main=Handler(Looper.getMainLooper())
    private var state=Bundle()
    private var receivedAt=0L
    private var closed=false
    private val prefs=context.getSharedPreferences("sentinel-events",0)
    private var lastAlertId=""
    private var lastStart=-15_000L
    private var configured=false
    private var lastCapture=false
    private var idleSequence=0L
    private val client=AutoClient(context,AutoContract.OBSERVER,
        onSnapshot={snapshot->
            if(!closed){
                if(snapshot.getInt("sentinelVersion")==1){state=snapshot.getBundle("sentinel")?:Bundle();receivedAt=state.getLong("at");update()}
                else {hub.sentinelState=Bundle().apply{putString("state","service_update_required")};hub.sentinelCapture(false)}
            }
        },onState={value->if(AutoServiceRecordFeed.linkFailure(value)){configured=false;receivedAt=0;hub.sentinelCapture(false)}},
        onReady={if(!closed)clientReady()})
    private val tick=object:Runnable{override fun run(){update();if(!closed)main.postDelayed(this,500)}}
    fun start(){client.connect();main.post(tick)}
    private fun clientReady(){
        client.configureSentinel(true){reply->
            configured=reply.getBoolean("accepted")
            if(!configured){hub.sentinelState=Bundle().apply{putString("state","prepare_failed");putString("error",reply.getString("error"))};hub.sentinelCapture(false)}
        }
    }
    private fun update(){
        if(closed)return
        val now=SystemClock.elapsedRealtime()
        val fresh=receivedAt>0&&now-receivedAt in 0..2500
        val value=if(fresh)Bundle(state)else Bundle().apply{putString("state","service_data_stale")}
        hub.sentinelState=value
        if(fresh){
            hub.suspendForPower(state.getInt("power") in setOf(2,5,7))
            val sequence=state.getLong("powerSequence")
            if(hub.powerIdle&&!hub.running&&sequence>0&&idleSequence!=sequence){client.reportSentinelIdle(sequence);idleSequence=sequence}
        }
        if(fresh&&!state.getBoolean("enabled")&&state.getString("state")=="low_battery"){
            val store=PrefsSettingsStore(context);store.save(store.load().copy(sentinelEnabled=false))
            hub.sentinelCapture(false);hub.settingsChanged();return
        }
        val capture=configured&&fresh&&state.getBoolean("capture")&&PrefsSettingsStore(context).load().sentinelEnabled
        if(!capture)hub.sentinelCapture(false)
        else if(!hub.running&&now-lastStart>=15_000){lastStart=now;hub.sentinelCapture(true)}
        if(capture&&!lastCapture){
            runCatching{context.startActivity(Intent(context,SentinelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
                .onFailure{RecordDiagnostics.event("sentinel_status_open",it)}
        }
        lastCapture=capture
        val alert=state.getLong("alertSequence")
        val alertId=state.getString("epoch").orEmpty()+":"+alert
        if(fresh&&capture&&alert>0&&alertId!=lastAlertId&&alertId!=prefs.getString("last",null)){
            if(alert>0){hub.protectSentinelEvent(state.getLong("alertAt")*1_000_000L,alert)
                runCatching{context.startActivity(Intent(context,SentinelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
                    .onFailure{RecordDiagnostics.event("sentinel_status_open",it)}}
            lastAlertId=alertId;prefs.edit().putString("last",alertId).apply()
        }
        hub.sentinelUpdated()
    }
    fun release(disarm:Boolean=true){
        if(closed)return
        closed=true;main.removeCallbacks(tick)
        if(disarm)client.configureSentinel(false){client.close()}else client.close()
        // 旧服务断开时回调可能不返回，延后关闭只清自己的Binder连接。
        main.postDelayed({client.close()},5000)
        hub.sentinelCapture(false);hub.suspendForPower(false)
    }
}
