package com.galaxy.autorecord.runtime

import android.app.Activity
import android.content.Intent
import android.os.*
import android.widget.FrameLayout
import com.galaxy.auto.client.recovery.ProcessRecovery
import com.galaxy.autorecord.core.*

/** Android11 while-in-use恢复入口。透明窗口取得真实前台资格，首批编码落盘后立即退出。 */
class RecordResumeActivity:Activity() {
    private lateinit var hub:RecordSessionHub
    private val handler=Handler(Looper.getMainLooper())
    private var started=false
    private val observer:(Boolean)->Unit={
        if(hub.snapshot.state==RecordingState.RECORDING&&hub.snapshot.bytesWritten>0||hub.snapshot.state==RecordingState.ERROR||!hub.hasWork||(hub.assistReady||hub.sentinelReady)&&!hub.wanted)finish()
    }
    private val timeout=Runnable{RecordDiagnostics.event("visible_recovery_timeout",IllegalStateException("recovery_first_frame_timeout"));finish()}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);hub=RecordSessionHub.get(this)
        if(!hub.hasWork||!ProcessRecovery.accepts(this,intent)){finish();return}
        setContentView(FrameLayout(this))
        hub.observe(observer)
    }
    override fun onResume(){
        super.onResume()
        if(isFinishing||started)return
        started=true
        window.decorView.postDelayed({
            if(isFinishing||!hub.hasWork)return@postDelayed
            runCatching{
                startForegroundService(Intent(this,RecordSessionService::class.java).setAction("visible-recovery")
                    .putExtra(ProcessRecovery.EXTRA_GENERATION,intent.getStringExtra(ProcessRecovery.EXTRA_GENERATION)))
            }.onFailure{RecordDiagnostics.event("visible_recovery_start",it);finish()}
        },200)
        handler.postDelayed(timeout,20_000)
    }
    override fun onDestroy(){handler.removeCallbacks(timeout);if(::hub.isInitialized)hub.removeObserver(observer);super.onDestroy()}
}
