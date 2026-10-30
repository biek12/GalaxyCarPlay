package com.galaxy.autocluster

import android.app.*
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import com.galaxy.auto.contract.AutoContract
import com.galaxy.autocluster.runtime.ClusterSceneOutput
import com.galaxy.autocluster.theme.*
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter

/** 持久主题运行时独立于Activity；每次启动从已应用快照恢复，草稿修改不影响当前输出。 */
class ClusterRenderService : Service() {
    private var output: ClusterSceneOutput?=null
    private var requested=false
    private val store by lazy { ThemeStore(File(filesDir,"themes"),BuiltinThemes.packages()) }
    override fun onCreate(){
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("cluster","仪表显示",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,ClusterActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(72,Notification.Builder(this,"cluster").setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("AutoCluster").setContentText("仪表主题运行中").setContentIntent(open).build())
        output=ClusterSceneOutput(this,AutoContract.THEME,onStatus={ android.util.Log.i("AutoCluster",it) })
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val prefs=getSharedPreferences("cluster-display",0)
        if(intent?.action=="stop"||!prefs.getBoolean("enabled",true)){
            startForegroundService(Intent("theme-stop").setComponent(ComponentName(AutoContract.SERVICE_PACKAGE,AutoContract.SERVICE_CLASS)))
            stopSelf();return START_NOT_STICKY
        }
        output?.setActive(true)
        if(!requested||intent?.action in setOf("apply","reload")||intent?.hasExtra("panel")==true){
            requested=true
            output?.loadTheme {
                // 保留早期面板入口；首次采用GT，已有活动快照优先。
                val legacy=intent?.getStringExtra("panel")?:getSharedPreferences("theme",0).getString("panel","MUSIC")
                val explicit=if(intent?.hasExtra("panel")==true)when(legacy){"TIRES"->"builtin-tires";"PEDALS"->"builtin-pedals";else->"builtin-gt"}else null
                if(explicit!=null)store.apply(explicit,1)else store.active()?:store.apply("builtin-gt",1)
            }
        }
        return START_STICKY
    }
    override fun onConfigurationChanged(config: Configuration){super.onConfigurationChanged(config);output?.refreshAppearance()}
    override fun onBind(intent: Intent?): IBinder?=null
    override fun dump(fd: FileDescriptor,writer: PrintWriter,args: Array<out String>){writer.println("AutoCluster ${output?.metrics()?:"inactive"}")}
    override fun onDestroy(){output?.close();output=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
