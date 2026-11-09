package com.ecarx.autoservice.diagnostics

import android.content.Context
import com.ecarx.autoservice.remote.RemoteHttp
import com.ecarx.autoservice.remote.RemoteSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.*

/** 单独诊断上传，不受12/24小时远程Shell授权窗口限制；总开关/自动运行关闭时不出网。 */
internal object DiagnosticUploader {
    private val scheduler=ScheduledThreadPoolExecutor(1){r->Thread(r,"diagnostic-upload").apply{isDaemon=true}}.apply {
        // 延迟队列常驻时保留一个休眠线程，避免核心超时导致周期性线程销毁/重建。
        removeOnCancelPolicy=true
    }
    private var task:ScheduledFuture<*>?=null
    @Synchronized fun kick(context:Context) {
        if(task!=null&&!task!!.isDone)return
        val app=context.applicationContext
        task=scheduler.scheduleWithFixedDelay({
            if(!app.getSharedPreferences("platform",0).getBoolean("automatic",true))return@scheduleWithFixedDelay
            if(!app.getSharedPreferences("diagnostics",0).getBoolean("uploadEnabled",true))return@scheduleWithFixedDelay
            runCatching{repeat(4){upload(app)}}.onFailure { error ->
                app.getSharedPreferences("diagnostics",0).edit().putString("lastUploadError",error.javaClass.name)
                    .putLong("lastUploadErrorAt",System.currentTimeMillis()).apply()
            } // 上传自身失败不递归生成错误风暴；已有记录保持待发。
        },10,15*60,TimeUnit.SECONDS)
    }
    private fun upload(context:Context) {
        // 未私有登记时不创建网络连接；隔离模拟器也不向公网发送测试错误。
        if(!File(context.noBackupFilesDir,"remote-enrollment").isFile)return
        val files=synchronized(DiagnosticInboxProvider.lock){File(context.noBackupFilesDir,"diagnostic-inbox").listFiles()
            .orEmpty().filter{it.extension=="json"}.sortedBy{it.lastModified()}.take(4)}
        if(files.isEmpty())return
        val records=JSONArray();files.forEach{records.put(JSONObject(it.readText()))}
        val settings=RemoteSettings(context)
        RemoteHttp(context,settings).use{http->
            val response=http.json("/telemetry/events",JSONObject().put("device",settings.id).put("events",records))
            context.getSharedPreferences("diagnostics",0).edit().putLong("lastUploadAt",System.currentTimeMillis())
                .remove("lastUploadError").apply()
            val ack=response.getJSONArray("accepted")
            val ids=(0 until ack.length()).map{ack.getString(it)}.toSet()
            synchronized(DiagnosticInboxProvider.lock){files.filter{it.nameWithoutExtension in ids}.forEach{it.delete()}}
        }
    }
}
