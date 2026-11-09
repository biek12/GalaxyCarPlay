package com.galaxy.autorecord.runtime

import android.app.*
import android.content.*
import android.os.IBinder
import com.galaxy.autorecord.R
import com.galaxy.autorecord.core.*
import com.galaxy.auto.client.recovery.ProcessRecovery

/** 持有camera类型FGS的服务；Sticky与进程外闹钟都只恢复尚未撤销的工作意图。 */
class RecordSessionService:Service() {
    private lateinit var hub:RecordSessionHub
    private var wake:android.os.PowerManager.WakeLock?=null
    private var lastNotification=""
    private var finishing=false
    private var recoveryAccess:com.galaxy.auto.client.AutoClient?=null
    private var requesting=false
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private val retry=Runnable{prepareVisibleRecovery()}
    private val screen=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent?){
        if(intent?.action==Intent.ACTION_SCREEN_OFF&&!PrefsSettingsStore(context).load().screenOff&&!hub.sentinelEnabled)hub.stopRequested()
    }}
    override fun onCreate(){
        super.onCreate();hub=RecordSessionHub.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("record-session","行车录制状态",NotificationManager.IMPORTANCE_LOW))
        startForeground(8169,notification())
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(screen,IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screen,IntentFilter(Intent.ACTION_SCREEN_OFF))
        hub.serviceChanged={
            val key="${hub.snapshot.state}:${hub.snapshot.segmentIndex}:${hub.snapshot.error?.code}:${hub.sentinelState.getString("state")}"
            if(key!=lastNotification){lastNotification=key;getSystemService(NotificationManager::class.java).notify(8169,notification())}
            if(!hub.wanted&&!hub.running){wake?.let{if(it.isHeld)it.release()};wake=null}
            if(!finishing&&!hub.hasWork&&!hub.running){finishing=true;ProcessRecovery.release(this,false);stopForeground(STOP_FOREGROUND_DETACH);stopSelf()}
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="record.start"){
            // 私有Service仅允许本App或已授权Root入口；用于同一产品录制入口的现场复测。
            hub.startRequested(SegmentLayout.DIRECTIONS,PrefsSettingsStore(this).load(),foregroundRequest=false)
            return START_STICKY
        }
        if(intent?.action=="stop"){hub.stopRequested();if(!hub.running&&!hub.hasWork)stopSelf();return if(hub.hasWork)START_STICKY else START_NOT_STICKY}
        if(!ProcessRecovery.accepts(this,intent)){if(!hub.running)stopSelf();return if(hub.running)START_STICKY else START_NOT_STICKY}
        if(!hub.hasWork){stopSelf();return START_NOT_STICKY}
        val enabled=PrefsSettingsStore(this).load().recoverAfterCrash
        if(intent==null&&!enabled){hub.stopRequested();stopSelf();return START_NOT_STICKY}
        if(enabled)ProcessRecovery.arm(this,Intent(this,RecordSessionService::class.java).setAction("recovery"),
            intent?.hasExtra(ProcessRecovery.EXTRA_GENERATION)==true)
        else ProcessRecovery.release(this,false)
        if(!hub.running && intent?.action !in setOf("start","visible-recovery","policy")){
            prepareVisibleRecovery()
            return if(enabled)START_STICKY else START_NOT_STICKY
        }
        hub.syncAssist()
        if(hub.wanted&&wake==null)wake=getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"AutoRecord:recording").apply{acquire()}
        hub.startInService()
        return if(enabled&&hub.hasWork)START_STICKY else START_NOT_STICKY
    }
    private fun prepareVisibleRecovery(){
        if(!hub.hasWork||finishing)return
        if(android.provider.Settings.canDrawOverlays(this)&&checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED){
            val generation=getSharedPreferences("process-recovery",0).getString("generation",null)
            ProcessRecovery.release(this,true)
            // SDK30允许已获SYSTEM_ALERT_WINDOW的自有App启动恢复页。不是持有CAMERA就绕过while-in-use。
            runCatching{startActivity(Intent(this,RecordResumeActivity::class.java).setAction("recovery")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(ProcessRecovery.EXTRA_GENERATION,generation))}
                .onFailure{RecordDiagnostics.event("recovery_visible_launch",it)}
            finishing=true;stopSelf();return
        }
        if(requesting)return
        requesting=true
        if(recoveryAccess==null){
            recoveryAccess=com.galaxy.auto.client.AutoClient(this,com.galaxy.auto.contract.AutoContract.OBSERVER,
                onSnapshot={},onState={},onReady={requesting=false;requestRecoveryPermissions()})
            recoveryAccess?.connect()
        }else {requesting=false;requestRecoveryPermissions()}
    }
    private fun requestRecoveryPermissions(){
        if(requesting||!hub.hasWork)return
        requesting=true
        recoveryAccess?.requestClientPermissions(listOf(android.Manifest.permission.CAMERA,android.Manifest.permission.SYSTEM_ALERT_WINDOW)) { result ->
            requesting=false
            if(result.getBoolean("granted")&&android.provider.Settings.canDrawOverlays(this))prepareVisibleRecovery()
            else {
                RecordDiagnostics.event("recovery_permission_prepare",IllegalStateException(result.getString("error")?:"recovery_permission_not_ready"))
                main.removeCallbacks(retry);main.postDelayed(retry,60_000)
            }
        }
    }
    private fun notification():Notification {
        val active=hub.snapshot.state==RecordingState.RECORDING
        val text=when(hub.snapshot.state){
            RecordingState.RECORDING->"录制中 · 第${hub.snapshot.segmentIndex}段"
            RecordingState.STARTING->"正在恢复/准备录制"
            RecordingState.STOPPING->"正在保存录像"
            RecordingState.ERROR->"录制异常：${hub.snapshot.error?.detail.orEmpty()}"
            else->if(hub.sentinelEnabled)"哨兵 · "+sentinelStateLabel(hub.sentinelState.getString("state")) else if(hub.assistEnabled)"转向辅助待机"else"已停止录制"
        }
        val open=PendingIntent.getActivity(this,0,Intent(this,com.galaxy.autorecord.ui.RecordActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,RecordSessionService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"record-session").setSmallIcon(R.drawable.record_icon_live)
            .setContentTitle("AutoRecord").setContentText(text).setContentIntent(open).setOnlyAlertOnce(true)
            .setColor(if(active)0xff2ec471.toInt() else 0xff888888.toInt()).setColorized(true)
            .setOngoing(hub.hasWork||hub.running).addAction(Notification.Action.Builder(null,"停止录制",stop).build()).build()
    }
    override fun dump(fd:java.io.FileDescriptor,writer:java.io.PrintWriter,args:Array<out String>){
        writer.println("state=${hub.snapshot.state} wanted=${hub.wanted} running=${hub.running} segment=${hub.snapshot.segmentIndex} bytes=${hub.snapshot.bytesWritten}")
        writer.println("cameras="+hub.snapshot.cameras)
        writer.println(com.galaxy.autorecord.recording.EngineDiagnostics.pipeline)
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){
        val resume=hub.hasWork&&PrefsSettingsStore(this).load().recoverAfterCrash
        ProcessRecovery.release(this,resume);hub.serviceDestroyed();hub.releasePreviewIfUnused()
        main.removeCallbacks(retry);recoveryAccess?.close();recoveryAccess=null
        runCatching{unregisterReceiver(screen)};wake?.let{if(it.isHeld)it.release()};wake=null
        super.onDestroy()
    }
}
class RecordBootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent?) {
        val settings=PrefsSettingsStore(context).load()
        val prefs=context.getSharedPreferences("record-session",0)
        if(intent?.action==Intent.ACTION_BOOT_COMPLETED&&!settings.autoOnBoot){prefs.edit().putBoolean("wanted",false).commit();if(!settings.turnAssist&&!settings.sentinelEnabled)return}
        if(intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        if(settings.autoOnBoot){check(prefs.edit().putBoolean("wanted",true).putStringSet("directions",SegmentLayout.DIRECTIONS.toSet()).commit())}
        if(settings.turnAssist || settings.sentinelEnabled || prefs.getBoolean("wanted",false)&&(settings.autoOnBoot||settings.recoverAfterCrash))runCatching{
            context.startForegroundService(Intent(context,RecordSessionService::class.java).setAction("boot"))
        }
            .onFailure{RecordDiagnostics.event("boot_record_start",it)}
    }
}
