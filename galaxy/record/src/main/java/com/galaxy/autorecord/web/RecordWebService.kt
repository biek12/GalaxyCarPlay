package com.galaxy.autorecord.web

import android.app.*
import android.content.*
import android.os.*
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.galaxy.autorecord.core.*
import com.galaxy.autorecord.runtime.RecordSessionHub
import org.json.*
import java.io.File

/** 在线业务消费者；媒体仍由AutoService凭私有身份出站，录制App不持有服务器凭据。 */
class RecordWebService:Service(){
    private val main=Handler(Looper.getMainLooper())
    private val thread=HandlerThread("record-web-index").apply{start()}
    private val io=Handler(thread.looper)
    private lateinit var client:AutoClient
    private lateinit var hub:RecordSessionHub
    private val vehicle=AutoVehicleFeed(SystemClock::elapsedRealtime)
    private var subscribed=false
    private var connected=false
    private var busy=false
    private var publishBusy=false
    private var jobsBusy=false
    private val mediaHolds=linkedMapOf<String,Pair<Long,java.io.Closeable>>()
    @Volatile private var closed=false
    private var library=LibraryState()
    private var scannedAt=-60000L
    private var revision=-1L
    private var lastBound=false
    private var root:File?=null
    private var live:WebRtcLive?=null
    private var lastPublishAt=-10000L
    private var liveError=""
    private var lastPoll=0L
    private var liveCount=0
    private val tick=object:Runnable{override fun run(){if(!closed&&connected&&!busy)poll();if(!closed)main.postDelayed(this,2000)}}
    override fun onCreate(){
        super.onCreate();hub=RecordSessionHub.get(this)
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("record-web","车辆在线服务",NotificationManager.IMPORTANCE_LOW))
        startForeground(8170,Notification.Builder(this,"record-web").setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("AutoRecord 在线服务").setContentText("同步状态与录像目录，按网页请求传输录像")
            .setContentIntent(PendingIntent.getActivity(this,8170,Intent(this,com.galaxy.auto.client.vehicle.VehicleAccountActivity::class.java),PendingIntent.FLAG_IMMUTABLE)).build())
        client=AutoClient(this,AutoContract.OBSERVER,onVehicleFeed={vehicle.accept(it)},onReady={connected=true;busy=false;vehicle.connected(true)},onSnapshot={state->
            if(state.containsKey("vehicleWebEnabled")&&!state.getBoolean("vehicleWebEnabled")) { client.vehicleWebRequest("state"){reply->val state=runCatching{JSONObject(reply.getString("json")?:"{}")}.getOrNull();if(state?.has("enabled")==true&&!state.optBoolean("enabled"))stopSelf()} }
        },onState={if(it.contains("disconnected"))connected=false})
        client.connect();main.post(tick)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onBind(intent:Intent?):IBinder?=null
    private fun request(action:String,value:JSONObject=JSONObject(),done:(JSONObject?)->Unit){
        client.vehicleWebRequest(action,Bundle().apply{putString("json",value.toString())}){reply->
            if(!closed)done(if(reply.containsKey("error"))null else runCatching{JSONObject(reply.getString("json")?:"{}")}.getOrNull())
        }
    }
    private fun poll(){
        busy=true
        request("record-poll"){value->
            if(value==null){busy=false;live?.close();live=null;return@request}
            busy=false
            val pendingJobs=value.optJSONArray("jobs")?:JSONArray()
            if(!jobsBusy){jobsBusy=true;runJobs(pendingJobs,0){jobsBusy=false}}
            val sessions=value.optJSONArray("liveSessions")?:JSONArray()
            lastPoll=SystemClock.elapsedRealtime();liveCount=sessions.length()
            val activeMedia=value.optJSONArray("activeMedia")?:JSONArray()
            for(i in 0 until activeMedia.length()){val id=activeMedia.optString(i);mediaHolds[id]?.let{mediaHolds[id]=lastPoll to it.second}}
            mediaHolds.entries.filter{lastPoll-it.value.first>45000}.map{it.key}.forEach{mediaHolds.remove(it)?.second?.close()}
            if(sessions.length()>0&&live==null&&checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED){
                live=runCatching{WebRtcLive(this,hub.camera()){action,payload->main.post{if(!closed)request(action,payload){}}}}.onFailure{
                    liveError=it.javaClass.simpleName+":"+it.message
                    android.util.Log.e("AutoRecordWeb","WebRTC initialization failed",it)
                    RecordDiagnostics.event("webrtc_initialize",it)
                }.getOrNull()
            }
            live?.update(sessions)
            if(sessions.length()==0){live?.close();live=null}
            if(publishBusy)return@request
            publishBusy=true
            val settings=PrefsSettingsStore(this).load()
            if((sessions.length()>0)!=subscribed){
                subscribed=sessions.length()>0
                client.setVehicleFeed(if(subscribed)RecordVehicleAdapter.selected(settings).flatMap(RecordVehicleAdapter::keys).distinct()else emptyList(),1000)
            }
            val liveSample=WebTelemetry.live(vehicle.snapshot())
            val nextRevision=hub.libraryRevision
            val snapshot=hub.snapshot
            val sentry=hub.sentinelState
            io.post{
                val volume=AndroidStorageVolumes(this).resolve(settings.storageVolumeUuid)
                val base=volume?.appDir
                val changed=value.optBoolean("bound")!=lastBound||base!=root||nextRevision!=revision||SystemClock.elapsedRealtime()-scannedAt>=60000
                if(changed){library=LocalLibrary.load(base,base?.let{SegmentMarks(SegmentLayout.indexDir(it))},volume?.label?:"未知");root=base;revision=nextRevision;lastBound=value.optBoolean("bound");scannedAt=SystemClock.elapsedRealtime()}
                val payload=JSONObject().put("snapshot",JSONObject().put("liveSample",liveSample)
                    .put("recording",JSONObject().put("state",if(snapshot.state==RecordingState.RECORDING)"recording" else "idle").put("startedAt",snapshot.startedAtMillis).put("observedAt",System.currentTimeMillis()))
                    .put("storage",JSONObject().put("volume",volume?.label?:"未知").put("usedBytes",library.totalBytes).put("quotaBytes",settings.effectiveQuotaBytes(volume?.totalBytes?:0)).put("freeBytes",base?.usableSpace?:0))
                    .put("sentry",JSONObject().put("actual",actualSentry(sentry,settings.sentinelEnabled)))
                    .put("capabilities",JSONObject().put("liveView",checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED).put("liveTransport","webrtc").put("liveFps",15).put("flatView",JSONObject().apply{val adapter=L6RecordCameraAdapter{CameraLayoutStore(this@RecordWebService).load()};val routes=adapter.routes();SegmentLayout.DIRECTIONS.forEach{d->val r=routes[d];put(d,r!=null&&com.galaxy.autorecord.preview.LensProfile.read(this@RecordWebService,d,r.id,r.width,r.height)!=null)}}).put("remoteWake",JSONObject().put("supported",false).put("reason","深睡远程唤醒尚未实车验收"))))
                if(value.optBoolean("bound")&&changed&&library.error==null)payload.put("clips",JSONArray().apply{
                    library.segments.filter{it.completeness!=SegmentCompleteness.WRITING&&it.durationMillis>0}.take(4096).forEach{put(clip(it,0))}
                })
                val clips=payload.optJSONArray("clips")
                payload.remove("clips")
                val indexFile=if(clips!=null)File(cacheDir,"web-index.json").also{it.writeText(JSONObject().put("clips",clips).toString())}else null
                main.post{if(!closed){
                    fun publish(done:()->Unit){
                        if(!changed&&SystemClock.elapsedRealtime()-lastPublishAt<10000){done();return}
                        request("record-publish",payload){if(it!=null)lastPublishAt=SystemClock.elapsedRealtime();done()}
                    }
                    publish {
                    fun jobs(){publishBusy=false}
                    if(indexFile!=null)client.vehicleWebUpload("index","index",indexFile){reply->
                        indexFile.delete();if(reply.containsKey("error"))scannedAt=-60000L;jobs()
                    }else jobs()
                }}}
            }
        }
    }
    private fun actualSentry(state:Bundle,enabled:Boolean):String=when{
        !enabled->"disarmed"
        state.getBoolean("capture")->"armed_recording"
        state.getString("state")=="low_battery"->"restricted"
        state.getBoolean("enabled")->"armed_standby"
        else->"unknown"
    }
    private fun clip(e:SegmentEntry,interval:Int):JSONObject {
        val manifest=com.galaxy.autorecord.export.ExportPlanner.readManifest(e.dir)
        return JSONObject().put("clipId",e.id).put("sessionId",e.id.substringBeforeLast('-'))
        .put("startAt",e.startedAtMillis).put("endAt",e.startedAtMillis+e.durationMillis).put("source","local")
        .put("availability",when(e.completeness){SegmentCompleteness.COMPLETE->"ok";SegmentCompleteness.RECOVERABLE->"recoverable";SegmentCompleteness.MISSING_FILE->"missing";else->"partial"})
        .put("locked",e.locked).put("eventCount",e.eventCount).put("bytes",e.bytes)
        .put("channels",JSONArray().apply{SegmentLayout.DIRECTIONS.forEach{d->put(JSONObject().put("direction",d).put("status",if(d in e.missingDirections)"missing" else "ok").put("mediaStartOffsetMs",(manifest?.channels?.firstOrNull{it.direction==d}?.firstPtsUs?:0)/1000).put("durationMs",e.durationMillis))}})
        .put("telemetry",JSONObject().put("schema","autorecord-v1").put("actualIntervalMs",interval).put("availability",if(e.hasVehicleData)"full" else "none"))
    }
    private fun runJobs(jobs:JSONArray,index:Int,done:()->Unit){
        if(closed)return
        if(index>=jobs.length()){done();return}
        val job=jobs.getJSONObject(index)
        request("record-result",JSONObject().put("jobId",job.getString("jobId")).put("state","running")){accepted->
            if(accepted==null)runJobs(jobs,index+1,done)else executeJob(jobs,index,done)
        }
    }
    private fun executeJob(jobs:JSONArray,index:Int,done:()->Unit){
        if(closed)return
        if(index>=jobs.length()){done();return}
        val job=jobs.getJSONObject(index)
        val result=JSONObject().put("jobId",job.getString("jobId"))
        if(job.optLong("expiresAt")<=System.currentTimeMillis()){runJobs(jobs,index+1,done);return}
        val payload=job.optJSONObject("payload")?:JSONObject()
        fun next(){request("record-result",result){runJobs(jobs,index+1,done)}}
        when(job.optString("type")){
            "clip-delete"->io.post{
                val items=JSONArray();val ids=payload.optJSONArray("clipIds")?:JSONArray()
                for(i in 0 until ids.length()){
                    val id=ids.optString(i);val e=library.find(id)
                    val item=JSONObject().put("ref",id).put("state","skipped")
                    if(payload.optString("scope")!="local")item.put("reason","删除范围不匹配")
                    else if(e==null)item.put("reason","片段不存在或已覆盖")
                    else runCatching{
                        val marks=SegmentMarks(SegmentLayout.indexDir(checkNotNull(root)))
                        var reason="片段使用中"
                        val deleted=SegmentAccess.delete(e.dir){
                            if(id in marks.locks()){reason="片段已锁定";false}
                            else if(e.completeness==SegmentCompleteness.WRITING){reason="片段正在写入";false}
                            else {e.dir.deleteRecursively().also{if(it)marks.forget(listOf(id))}}
                        }
                        item.put("state",if(deleted)"success" else "skipped").put("reason",if(deleted)"" else reason)
                    }.onFailure{item.put("state","failed").put("reason","文件删除失败")}
                    items.put(item)
                }
                scannedAt=-60000L;result.put("state","success").put("items",items)
                main.post{if(!closed)next()}
            }
            "clip-prepare"->{
                val entry=library.find(payload.optString("clipId"))
                if(entry==null){result.put("state","failed").put("reason","片段已覆盖或不存在");next()}
                else {
                    val hold=runCatching{SegmentAccess.hold(entry.dir)}.getOrNull()
                    if(hold==null){result.put("state","failed").put("reason","片段正在删除");next()}
                    else {
                        val directions=SegmentLayout.DIRECTIONS.filter{File(entry.dir,"$it.mp4").isFile}
                        fun upload(at:Int){
                            if(closed){hold.close();return}
                            if(at>=directions.size){
                                io.post{
                                    val data=WebTelemetry.encode(entry)
                                    main.post{if(!closed)request("record-telemetry",JSONObject().put("clipId",entry.id).put("telemetry",data)){
                                        mediaHolds.put(entry.id,SystemClock.elapsedRealtime() to hold)?.second?.close();result.put("state","success");next()
                                    }else hold.close()}
                                };return
                            }
                            val direction=directions[at]
                            client.vehicleWebUpload(entry.id,"open-$direction",File(entry.dir,"$direction.mp4")){reply->
                                if(reply.containsKey("error")){hold.close();result.put("state","failed").put("reason","媒体传输失败，请重试");next()}else upload(at+1)
                            }
                        }
                        if(directions.isEmpty()){hold.close();result.put("state","failed").put("reason","没有可播放的终态原片");next()}else upload(0)
                    }
                }
            }
            "sentry"->{
                val enabled=payload.optBoolean("enabled")
                if(enabled&&checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){result.put("state","failed").put("reason","请先在车端授予相机权限");next()}
                else{
                    val store=PrefsSettingsStore(this);store.save(store.load().copy(sentinelEnabled=enabled));hub.settingsChanged()
                    main.postDelayed({if(!closed){
                        val actual=actualSentry(hub.sentinelState,store.load().sentinelEnabled)
                        result.put("state",if(enabled&&actual !in listOf("armed_standby","armed_recording"))"failed" else "success")
                            .put("actual",actual).put("reason",if(actual=="unknown")"车端哨兵尚未准备就绪" else "")
                        next()
                    }},2000)
                }
            }
            else->{result.put("state","failed").put("reason","车端不支持此作业");next()}
        }
    }
    override fun dump(fd:java.io.FileDescriptor,writer:java.io.PrintWriter,args:Array<out String>){
        writer.println("connected=$connected busy=$busy publishBusy=$publishBusy jobsBusy=$jobsBusy liveSessions=$liveCount liveCreated=${live!=null} pollAgeMs=${SystemClock.elapsedRealtime()-lastPoll} clips=${library.segments.size} error=$liveError")
    }
    override fun onDestroy(){closed=true;mediaHolds.values.forEach{it.second.close()};mediaHolds.clear();live?.close();live=null;main.removeCallbacksAndMessages(null);client.close();io.removeCallbacksAndMessages(null);thread.quitSafely();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
