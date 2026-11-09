package com.ecarx.autoservice.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Android11系统退出接口未给native trace时，只补读本App/PID/故障时窗的crash栈。
 * 不收集寄存器、内存转储、其他App消息或任意logcat正文；没有权限/证据时明确缺失。
 */
internal object NativeCrashContext {
    private val epoch=Regex("^\\s*(\\d+\\.\\d+)\\s+")
    private val message=Regex("\\s+[VDIWEF]\\s+[^:]+:\\s?(.*)$")
    private val pid=Regex("\\bpid:\\s*(\\d+)")
    fun parse(lines:Sequence<String>, app:String, previousPid:Int, exitAt:Long):JSONObject {
        var selected=false
        val frames=JSONArray()
        var signal:String?=null
        for(line in lines.take(2000)) {
            val time=epoch.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.times(1000)?.toLong()?:continue
            if(time<exitAt-15_000 || time>exitAt+5_000)continue
            val text=message.find(line)?.groupValues?.get(1)?.trim()?:continue
            if(text.contains(">>>") && text.contains("<<<")) {
                selected=pid.find(text)?.groupValues?.get(1)?.toIntOrNull()==previousPid && text.contains(">>> $app <<<")
            }
            if(!selected)continue
            if(text.startsWith("signal "))signal=clean(text)
            if(text.matches(Regex("#\\d+\\s+pc\\s+.*")) && frames.length()<128)frames.put(clean(text))
        }
        return JSONObject().put("source","system_crash_log_filtered").put("available",frames.length()>0)
            .put("targetPid",previousPid).put("signal",signal).put("frames",frames)
            .put("reason",if(frames.length()>0)"matched_app_pid_time"else"matching_trace_not_retained")
    }
    private fun clean(text:String):String = text.take(512).replace(Regex("/[^\\s()]+")) {
        val name=it.value.substringAfterLast('/')
        if(name.matches(Regex("lib[A-Za-z0-9_.-]+\\.so"))) "[library]$name" else "[path]"
    }
    fun capture(context:Context,app:String,previousPid:Int,exitAt:Long):JSONObject {
        if(context.checkSelfPermission(android.Manifest.permission.READ_LOGS)!=PackageManager.PERMISSION_GRANTED)
            return JSONObject().put("available",false).put("reason","read_logs_not_granted")
        var process:Process?=null
        var readerThread:Thread?=null
        return try {
            val child=ProcessBuilder("logcat","-b","crash","-d","-t","2000","-v","epoch","-s","DEBUG:F").redirectErrorStream(true).start()
            process=child
            val task=FutureTask {child.inputStream.bufferedReader().use{parse(it.lineSequence(),app,previousPid,exitAt)}}
            readerThread=Thread(task,"diagnostic-native-trace").apply{isDaemon=true;start()}
            task.get(2,TimeUnit.SECONDS)
        } catch(_:Exception) {JSONObject().put("available",false).put("reason","capture_failed_or_timeout")}
        finally {
            process?.destroyForcibly()
            runCatching{process?.inputStream?.close()}
            runCatching{readerThread?.join(100)}
        }
    }
}
