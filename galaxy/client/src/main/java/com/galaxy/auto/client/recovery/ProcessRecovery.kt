package com.galaxy.auto.client.recovery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.UUID

/** 自有FGS的进程外恢复闹钟；native崩溃无需经过Java handler。主动停止先撤销持久意图。 */
object ProcessRecovery {
    const val EXTRA_GENERATION="galaxy.recovery.generation"
    private var app:Context?=null
    private var target:Intent?=null
    private var ticket:PendingIntent?=null
    @Volatile private var alive=false
    private var healthy:Runnable?=null
    private val main=Handler(Looper.getMainLooper())
    private val beat=object:Runnable {
        override fun run(){synchronized(this@ProcessRecovery){if(!alive)return; schedule(watchDelay());main.postDelayed(this,10_000)}}
    }
    /** 接收旧闹钟时先校验代次/用户意图；同一活服务不能被再次启动。必须在arm之前调用。 */
    @Synchronized fun accepts(context:Context,intent:Intent?):Boolean {
        val generation=intent?.getStringExtra(EXTRA_GENERATION)?:return true
        val prefs=context.getSharedPreferences("process-recovery",0)
        if(!prefs.getBoolean("wanted",false)||prefs.getString("generation",null)!=generation)return false
        return !(alive&&prefs.getInt("pid",0)==Process.myPid())
    }
    @Synchronized fun arm(context:Context,intent:Intent,recovering:Boolean=false,activity:Boolean=false) {
        val component=checkNotNull(intent.component)
        require(component.packageName==context.packageName)
        val prefs=context.getSharedPreferences("process-recovery",0)
        val failures=if(recovering)(prefs.getInt("failures",0)+1).coerceAtMost(20)else 0
        val generation=UUID.randomUUID().toString()
        check(prefs.edit().putBoolean("wanted",true).putString("generation",generation)
            .putString("component",component.className).putString("action",intent.action).putBoolean("activity",activity)
            .putInt("pid",Process.myPid()).putInt("failures",failures).putLong("armedAt",System.currentTimeMillis()).commit())
        app=context.applicationContext
        target=Intent(intent).putExtra(EXTRA_GENERATION,generation)
        ticket=if(activity)PendingIntent.getActivity(context,8168,target!!,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            else PendingIntent.getForegroundService(context,8168,target!!,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alive=true;main.removeCallbacks(beat);healthy?.let{main.removeCallbacks(it)};schedule(watchDelay());main.postDelayed(beat,10_000)
        // 连续崩溃只退避，不偷偷关闭用户功能；稳定一分钟后恢复正常间隔。
        healthy=Runnable{if(alive&&prefs.getString("generation",null)==generation)prefs.edit().putInt("failures",0).apply()}
        main.postDelayed(healthy!!,60_000)
    }
    @Synchronized fun crashed() {
        if(!alive)return
        val failures=app?.getSharedPreferences("process-recovery",0)?.getInt("failures",0)?:0
        alive=false;main.removeCallbacks(beat);healthy?.let{main.removeCallbacks(it)};healthy=null
        schedule((3_000L shl failures.coerceAtMost(5)).coerceAtMost(60_000))
    }
    @Synchronized fun release(context:Context,keepWanted:Boolean) {
        alive=false;main.removeCallbacks(beat);healthy?.let{main.removeCallbacks(it)};healthy=null
        if(!keepWanted) {
            check(context.getSharedPreferences("process-recovery",0).edit().putBoolean("wanted",false)
                .remove("generation").commit())
            val alarm=context.getSystemService(AlarmManager::class.java)
            val prefs=context.getSharedPreferences("process-recovery",0)
            val component=prefs.getString("component",null)
            val pending=ticket?:component?.let {
                val intent=Intent().setComponent(ComponentName(context.packageName,it)).setAction(prefs.getString("action",null))
                if(prefs.getBoolean("activity",false))PendingIntent.getActivity(context,8168,intent,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                else PendingIntent.getForegroundService(context,8168,intent,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            }
            if(pending!=null){alarm.cancel(pending);pending.cancel()}
        }
    }
    private fun watchDelay():Long {
        val failures=app?.getSharedPreferences("process-recovery",0)?.getInt("failures",0)?:0
        return maxOf(30_000,(3_000L shl failures.coerceAtMost(5)).coerceAtMost(60_000))
    }
    private fun schedule(delay:Long) {
        val context=app?:return;val pending=ticket?:return
        val alarm=context.getSystemService(AlarmManager::class.java)
        val at=SystemClock.elapsedRealtime()+delay
        if(android.os.Build.VERSION.SDK_INT<31||alarm.canScheduleExactAlarms())alarm.setExact(AlarmManager.ELAPSED_REALTIME,at,pending)
        else alarm.setWindow(AlarmManager.ELAPSED_REALTIME,at,10_000,pending)
    }
}
