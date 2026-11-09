package com.ecarx.autoservice.diagnostics

import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** 仅同签名四个自有包可提交，来源由Binder UID回读，不相信客户端自行声明的app。 */
class DiagnosticInboxProvider : ContentProvider() {
    override fun onCreate():Boolean { return true }
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        val app=context!!; val uid=Binder.getCallingUid()
        check(app.packageManager.checkSignatures(uid,Process.myUid())==PackageManager.SIGNATURE_MATCH)
        val packages=app.packageManager.getPackagesForUid(uid).orEmpty().toSet()
        if(method=="status") {
            val records=synchronized(lock){File(app.noBackupFilesDir,"diagnostic-inbox").listFiles().orEmpty()
                .filter{it.extension=="json"}.mapNotNull{runCatching{JSONObject(it.readText())}.getOrNull()}
                .filter{it.optString("app") in packages}}
            val targetPid=extras?.getInt("targetPid")?:0
            val afterAt=extras?.getLong("afterAt")?:0
            val matchedExits=records.filter{it.optString("kind")=="recovered_exit" &&
                it.optJSONObject("extra")?.let{extra->extra.optInt("previousPid")==targetPid && extra.optLong("exitAt")>=afterAt}==true}
            return Bundle().apply {
                putInt("javaProbeMatches",records.count{it.optString("kind")=="java_crash" && it.optInt("pid")==targetPid && it.optLong("at")>=afterAt})
                putInt("exitProbeMatches",matchedExits.size)
                putInt("nativeProbeMatches",matchedExits.count{it.optJSONObject("nativeTrace")?.optBoolean("available")==true})
                putInt("events",records.size)
                putInt("javaCrashes",records.count{it.optString("kind")=="java_crash"})
                putInt("recoveredExits",records.count{it.optString("kind")=="recovered_exit"})
                putBoolean("probe",records.any{it.optString("operation")=="diagnostic_probe"})
                putInt("nativeCrashes",records.count{it.optJSONObject("extra")?.optInt("reason")==5})
                putInt("nativeTraces",records.count{it.optJSONObject("nativeTrace")?.optBoolean("available")==true})
            }
        }
        require(method=="append")
        val text=checkNotNull(extras?.getString("event"));require(text.toByteArray().size<=64*1024)
        val record=JSONObject(text);val name=record.getString("app")
        require(name in setOf("com.ecarx.autoservice","com.ecarx.carplay","com.galaxy.autorecord","com.galaxy.autocluster") && name in packages)
        val id=record.getString("id");require(id.matches(Regex("[a-f0-9-]{36}")))
        record.put("sourceUid",uid).put("receivedAt",System.currentTimeMillis())
        synchronized(lock) {
            val root=File(app.noBackupFilesDir,"diagnostic-inbox").apply{mkdirs()}
            val file=File(root,"$id.json")
            if(!file.exists()) {
                val extra=record.optJSONObject("extra")
                if(record.optString("kind")=="recovered_exit" && extra?.optInt("reason")==5 && (!extra.optBoolean("traceAvailable") || extra.optString("trace").isBlank())) {
                    record.put("nativeTrace",NativeCrashContext.capture(app,name,extra.optInt("previousPid"),extra.optLong("exitAt")))
                    if(record.toString().toByteArray().size>64*1024)
                        record.put("nativeTrace",JSONObject().put("available",false).put("reason","event_size_budget"))
                }
                // 中继故障不删除尚未ACK的旧记录；满额拒绝，来源保留本地outbox。
                val bytes=record.toString().toByteArray(Charsets.UTF_8)
                check(root.listFiles().orEmpty().sumOf{it.length()}+bytes.size<=8*1024*1024){"diagnostic_inbox_full"}
                val atomic=AtomicFile(file);val stream=atomic.startWrite()
                try{stream.write(bytes);atomic.finishWrite(stream)}catch(e:Exception){atomic.failWrite(stream);throw e}
            }
        }
        DiagnosticUploader.kick(app)
        return Bundle().apply{putBoolean("accepted",true)}
    }
    override fun query(u:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor?=null
    override fun getType(u:Uri):String?=null
    override fun insert(u:Uri,v:ContentValues?):Uri?=null
    override fun delete(u:Uri,s:String?,a:Array<out String>?)=0
    override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
    companion object{val lock=Any()}
}
