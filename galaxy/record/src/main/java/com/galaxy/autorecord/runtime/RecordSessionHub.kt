package com.galaxy.autorecord.runtime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import com.galaxy.autorecord.BuildConfig
import com.galaxy.autorecord.core.*
import com.galaxy.autorecord.recording.RecordingEngine
import com.galaxy.autorecord.storage.FinishOutcome
import java.io.File

/** Application上下文持有单一录制会话；页面只借预览Surface并观察状态，销毁页面不释放录制。 */
class RecordSessionHub private constructor(private val app:Context) {
    private val main=Handler(Looper.getMainLooper())
    private val prefs=app.getSharedPreferences("record-session",0)
    private val observers=linkedSetOf<(Boolean)->Unit>()
    private val store=PrefsSettingsStore(app)
    private var source:SurfaceCameraSource?=null
    @Volatile private var engine:RecordingEngine?=null
    private var serviceFeed:AutoServiceRecordFeed?=null
    private var currentSettings=store.load()
    private var activeDirections=emptyList<String>()
    private var pending:Pair<List<String>,RecordSettings>?=null
    private var assist:TurnAssistRuntime?=null
    private var sentinel:SentinelRuntime?=null
    private var sentinelWanted=false
    private var sentinelOwned=false
    private var powerSuspended=false
    var powerIdle=false;private set
    fun suspendForPower(value:Boolean){
        source?.suspendForPower(value)
        if(powerSuspended==value)return
        powerSuspended=value;powerIdle=false
        if(value){
            sentinelWanted=false;pending=null
            assist?.release();assist=null;source?.stop()
            if(engine!=null&&!stopping){stopping=true;snapshot=snapshot.copy(state=RecordingState.STOPPING);engine?.requestStop()}
            source?.awaitIdle{main.post{if(powerSuspended){powerIdle=true;emit(false)}}}?:run{powerIdle=true}
            emit(false)
        }else {
            stopping=false;syncAssist()
            if(prefs.getBoolean("wanted",false)&&engine==null)startRequested(prefs.getStringSet("directions",emptySet()).orEmpty().toList(),store.load())
        }
    }
    var sentinelState=android.os.Bundle();internal set
    val sentinelEnabled get()=store.load().sentinelEnabled
    val sentinelReady get()=sentinel!=null
    fun sentinelUpdated(){emit(false)}
    private var pendingSentinelEvent:Pair<Long,Long>?=null
    fun protectSentinelEvent(atNanos:Long,sequence:Long){
        if(engine!=null)engine?.markSentinel(atNanos,sequence)else pendingSentinelEvent=atNanos to sequence
    }
    fun sentinelCapture(value:Boolean){
        if(value){
            sentinelWanted=true
            if(engine==null&&pending==null){sentinelOwned=true;startRequested(SegmentLayout.DIRECTIONS,store.load(),sentinelRequest=true)}
        }else{
            sentinelWanted=false
            if(sentinelOwned){sentinelOwned=false;if(!prefs.getBoolean("wanted",false))stopRequested(false)}
        }
    }
    val assistEnabled get()=store.load().turnAssist
    val hasWork get()=wanted||assistEnabled||sentinelEnabled
    val assistReady get()=assist!=null
    private var stopping=false
    private var statusAt=0L
    private var statusKey=""
    var serviceChanged:(()->Unit)?=null
    @Volatile var snapshot=RecordingSnapshot();private set
    var libraryRevision=0L;private set
    val wanted get()=(prefs.getBoolean("wanted",false)||sentinelWanted)&&!powerSuspended
    val testMode get()=BuildConfig.EMULATOR_CAMERA && app.getSharedPreferences("emulator.record.test",0).getBoolean("enabled",false)
    val running get()=engine!=null
    val port=object:RecordingEnginePort {
        override fun start(directions:List<String>,settings:RecordSettings,listener:RecordingEngineListener) {
            startRequested(directions,settings)
        }
        override fun requestStop(){stopRequested()}
        override fun release()=Unit // 页面不拥有会话。
    }
    fun observe(callback:(Boolean)->Unit){observers.add(callback)}
    fun removeObserver(callback:(Boolean)->Unit){observers.remove(callback)}
    private fun emit(sessionChanged:Boolean=true){
        val key="${snapshot.state}:$wanted"
        if(testMode&&(key!=statusKey||android.os.SystemClock.elapsedRealtime()-statusAt>=1000)){
            statusKey=key
            statusAt=android.os.SystemClock.elapsedRealtime()
            AtomicFiles.writeAtomically(File(app.cacheDir,"record-session-test-status.json"),org.json.JSONObject()
                .put("pid",android.os.Process.myPid()).put("at",System.currentTimeMillis()).put("state",snapshot.state.name)
                .put("bytes",snapshot.bytesWritten).put("segmentIndex",snapshot.segmentIndex).put("wanted",wanted)
                .put("error",snapshot.error?.detail).toString().toByteArray())
        }
        observers.toList().forEach{it(sessionChanged)};serviceChanged?.invoke()
    }
    fun camera():SurfaceCameraSource {
        check(app.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
        return source?:SurfaceCameraSource(app,if(testMode)object:RecordCameraAdapter {
            override val label="隔离模拟器恢复测试（单路真实Camera2）"
            override fun routes()=mapOf("front" to RecordCameraRoute(app.getSystemService(CameraManager::class.java).cameraIdList.first(),1280,720))
        }else L6RecordCameraAdapter{CameraLayoutStore(app).load()}) {
            main.post {
                source?.let{snapshot=snapshot.copy(cameras=it.statuses().mapValues{entry->entry.value.state})}
                emit(false)
            }
        }.also{source=it}
    }
    fun startRequested(directions:List<String>,settings:RecordSettings,foregroundRequest:Boolean=true,sentinelRequest:Boolean=false) {
        if(engine!=null||pending!=null)return
        val selected=directions.filter{it in SegmentLayout.DIRECTIONS}.distinct()
        require(selected.isNotEmpty())
        val edit=prefs.edit().putStringSet("directions",selected.toSet())
        if(!sentinelRequest)edit.putBoolean("wanted",true)
        check(edit.commit())
        pending=selected to settings
        snapshot=RecordingSnapshot(state=RecordingState.STARTING);emit()
        try{app.startForegroundService(Intent(app,RecordSessionService::class.java).setAction(if(foregroundRequest)"start"else"bootstrap"))}
        catch(e:Exception){pending=null;failed("foreground_service_start",e)}
    }
    /** 服务在完成前台声明后调用；恢复只使用之前明确选择的方向，不替用户减少摄像头。 */
    fun startInService() {
        if(engine!=null)return
        if(!wanted){pending=null;return}
        stopping=false
        val recovering=pending==null
        val selected=pending?.first?:prefs.getStringSet("directions",emptySet()).orEmpty().toList().sorted()
        val loaded=pending?.second?:store.load()
        val settings=if(testMode)loaded.copy(segmentSeconds=4)else loaded
        pending=null
        currentSettings=settings;activeDirections=selected
        try {
            require(selected.isNotEmpty())
            check(app.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){"camera_permission_missing"}
            val camera=camera()
            val root=checkNotNull(AndroidStorageVolumes(app).resolve(settings.storageVolumeUuid)?.appDir)
            if(recovering)InterruptedSegments.finalizeEvidence(root,prefs.getLong("sessionStartedAt",Long.MAX_VALUE),prefs.getInt("sessionPid",0))
            check(prefs.edit().putLong("sessionStartedAt",System.currentTimeMillis()).putInt("sessionPid",android.os.Process.myPid()).commit())
            val environment=DeviceRecordingEnvironment(AndroidStorageVolumes(app),{camera},{CameraLayoutStore(app).load()},true)
            if(!testMode)environment.checkCameraLayout()?.let{error(it.detail)}
            environment.checkStorage(settings)?.let{error(it.detail)}
            if(!testMode)environment.checkEncoder(settings)?.let{error(it.detail)}
            val feed:VehicleFeed=if(testMode)OfflineVehicleFeed("隔离恢复测试，未连接车辆") else {
                AutoServiceRecordFeed(app,{currentSettings},onReady={serviceFeed?.authorize(true)},onLost={},onChanged={},recordUpdates=true).also{
                    serviceFeed=it;it.foreground(true);it.connect()
                }
            }
            val recorder=RecordingEngine(AndroidStorageVolumes(app),feed,camera,{currentSettings},BuildConfig.VERSION_NAME,
                encoderPicker=if(testMode)({CodecCapabilities.encoders(RecordSettings.CODEC_H264).first{it.name=="OMX.google.h264.encoder"}})
                    else ({CodecCapabilities.pick(it.codecMime,it.encoderImplementation)}))
            engine=recorder
            snapshot=RecordingSnapshot(state=RecordingState.STARTING,cameras=SegmentLayout.DIRECTIONS.associateWith{if(it in selected)CameraState.WAITING_FIRST_FRAME else CameraState.STOPPED})
            emit()
            recorder.start(selected,settings,object:RecordingEngineListener {
                private fun post(block:()->Unit){main.post{if(engine===recorder)block()}}
                override fun onSegmentStarted(index:Int,segmentDir:File){post{
                    snapshot=snapshot.copy(segmentIndex=index)
                    pendingSentinelEvent?.let{(at,id)->recorder.markSentinel(at,id);pendingSentinelEvent=null};emit()
                }}
                override fun onRecordingActive(){post{snapshot=snapshot.copy(state=RecordingState.RECORDING,
                    startedAtMillis=if(snapshot.startedAtMillis>0)snapshot.startedAtMillis else System.currentTimeMillis(),error=null);emit()}}
                override fun onProgress(bytesWritten:Long,segmentElapsedMs:Long,segmentIndex:Int){post{
                    snapshot=snapshot.copy(bytesWritten=bytesWritten,segmentElapsedMillis=segmentElapsedMs,segmentIndex=segmentIndex);emit()}}
                override fun onSegmentFinished(index:Int,outcome:FinishOutcome){post{libraryRevision++;emit()}}
                override fun onStopped(error:String?){post{
                    engine=null;recorder.release();serviceFeed?.release();serviceFeed=null
                    if(!powerSuspended)check(prefs.edit().putBoolean("wanted",false).commit())
                    snapshot=if(error==null)RecordingSnapshot()else snapshot.copy(state=RecordingState.ERROR,
                        error=RecordingError("engine_error","录制引擎","engine",error,true))
                    emit()
                }}
            })
        }catch(e:Exception){failed("session_start",e)}
    }
    private fun failed(stage:String,error:Exception) {
        check(prefs.edit().putBoolean("wanted",false).commit())
        pending=null;serviceFeed?.release();serviceFeed=null
        RecordDiagnostics.event(stage,error)
        snapshot=snapshot.copy(state=RecordingState.ERROR,error=RecordingError(stage,"录制服务",stage,error.message?:error.javaClass.simpleName,true));emit()
    }
    fun stopRequested(stopSentinel:Boolean=true) {
        if(stopSentinel&&sentinelEnabled){store.save(store.load().copy(sentinelEnabled=false));sentinel?.release();sentinel=null}
        sentinelWanted=false;sentinelOwned=false
        // 同步撤销意图，使已排队的恢复/系统sticky回调也不能重新开始。
        check(prefs.edit().putBoolean("wanted",false).commit());pending=null
        if(!assistEnabled)com.galaxy.auto.client.recovery.ProcessRecovery.release(app,false)
        if(engine!=null&&!stopping){stopping=true;snapshot=snapshot.copy(state=RecordingState.STOPPING);emit();engine?.requestStop()}
        else if(engine==null){snapshot=RecordingSnapshot();emit()}
    }
    fun clearError(){if(engine==null){snapshot=RecordingSnapshot();emit()}}
    fun settingsChanged(){
        if(!store.load().recoverAfterCrash)com.galaxy.auto.client.recovery.ProcessRecovery.release(app,false)
        if(running||assistEnabled||assist!=null||sentinelEnabled||sentinel!=null)app.startForegroundService(Intent(app,RecordSessionService::class.java).setAction("policy"))
    }
    fun syncAssist() {
        if(sentinelEnabled&&sentinel==null){sentinel=SentinelRuntime(app,this).also{it.start()};emit(false)}
        else if(!sentinelEnabled&&sentinel!=null){sentinel?.release();sentinel=null;emit(false)}
        if(assistEnabled && !powerSuspended && assist==null) {
            if(app.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return
            assist=TurnAssistRuntime(app,camera()).also{it.start()};emit(false)
        } else if((!assistEnabled||powerSuspended) && assist!=null){assist?.release();assist=null;emit(false)}
    }
    fun serviceDestroyed() {
        sentinel?.release(false);sentinel=null;sentinelWanted=false;sentinelOwned=false
        assist?.release();assist=null
        serviceChanged=null
        engine?.release();engine=null;serviceFeed?.release();serviceFeed=null
        // 异常系统回收保留持久意图；正常停止已在stopRequested中清除。
    }
    fun releasePreviewIfUnused(){if(observers.isEmpty()&&engine==null&&assist==null&&sentinel==null)source?.stop()}
    companion object {
        @Volatile private var instance:RecordSessionHub?=null
        fun get(context:Context):RecordSessionHub=instance?:synchronized(this){instance?:RecordSessionHub(context.applicationContext).also{instance=it}}
    }
}
