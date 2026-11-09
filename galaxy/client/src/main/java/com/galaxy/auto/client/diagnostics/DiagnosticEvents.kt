package com.galaxy.auto.client.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.*

/** 自有应用共用本地outbox；先持久化，ACK后删除。崩溃现场不做Binder/网络。 */
object DiagnosticEvents {
    private var app: Context? = null
    @Volatile private var apkSha256="unavailable_before_initialization"
    private var previousRun: JSONObject? = null
    private val worker=ThreadPoolExecutor(0,1,20,TimeUnit.SECONDS,ArrayBlockingQueue(8),{r->Thread(r,"diagnostic-outbox")})
    private val sending=java.util.concurrent.atomic.AtomicBoolean()
    private val retryScheduled=java.util.concurrent.atomic.AtomicBoolean()
    private val recent=linkedMapOf<String,Long>()
    private val names=setOf("com.galaxy.autorecord","com.galaxy.autocluster","com.ecarx.carplay","com.ecarx.autoservice")
    @Synchronized fun initialize(context: Context) {
        if(app!=null || context.packageName !in names)return
        app=context.applicationContext
        val prefs=context.getSharedPreferences("diagnostics",0)
        previousRun=runCatching{JSONObject(prefs.getString("lastRun","").orEmpty())}.getOrNull()
        val old=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread,error ->
            runCatching { persist("java_crash", "uncaught",error,mapOf("thread" to thread.name)) }
            runCatching { com.galaxy.auto.client.recovery.ProcessRecovery.crashed() }
            // 实测默认崩溃处理已打印FATAL但等待系统对话框，进程仍持续存活。
            // 本地留证/恢复闹钟已完成后，只给自有后台服务进程设置终止期限。
            if(app?.packageName in setOf("com.galaxy.autorecord","com.ecarx.autoservice")) {
                Thread({
                    try { Thread.sleep(1500) } catch(_:InterruptedException) { }
                    Process.killProcess(Process.myPid())
                },"diagnostic-fatal-exit").apply{isDaemon=true;start()}
            }
            old?.uncaughtException(thread,error)
        }
        // 初始化provider不能阻塞App首帧；原生SIGSEGV由恢复后的系统退出记录补报。
        worker.execute {
          runCatching {
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().use { input ->
                val bytes=ByteArray(65536)
                while(true){val count=input.read(bytes);if(count<0)break;digest.update(bytes,0,count)}
            }
            apkSha256=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
          }
          runCatching { recover() }
          runCatching {
            val version=context.packageManager.getPackageInfo(context.packageName,0)
            prefs.edit().putString("lastRun",JSONObject().put("pid",Process.myPid()).put("at",System.currentTimeMillis())
                .put("apkSha256",apkSha256).put("version",version.versionName).toString()).commit()
          }
          runCatching { flush() }
        }
    }
    fun error(operation: String,error: Throwable,parameters: Map<String,Any?> = emptyMap()) {
        if(app==null)return
        val key=operation+error.javaClass.name+error.stackTrace.firstOrNull()
        synchronized(recent) {
            val now=SystemClock.elapsedRealtime()
            if(now-(recent[key]?:-60_000)<60_000)return
            if(recent.size>=128)recent.remove(recent.keys.first())
            recent[key]=now
        }
        runCatching { persist("error",operation,error,parameters) }
        send()
    }
    private fun persist(kind:String,operation:String,error:Throwable?,parameters:Map<String,Any?>,extra:JSONObject?=null) {
        val context=app?:return
        val version=context.packageManager.getPackageInfo(context.packageName,0)
        val record=JSONObject().put("id",UUID.randomUUID().toString()).put("schema",1)
            .put("app",context.packageName).put("apkSha256",apkSha256).put("version",version.versionName).put("versionCode",version.longVersionCode)
            .put("kind",kind).put("operation",safe(operation).take(256)).put("at",System.currentTimeMillis())
            .put("elapsedMs",SystemClock.elapsedRealtime()).put("pid",Process.myPid())
            .put("process",safe(Application.getProcessName()).take(256)).put("sdk",Build.VERSION.SDK_INT)
            .put("parameters",parameters(parameters)).put("error",error?.let { throwable(it) })
            .put("extra",extra).put("evictedBefore",context.getSharedPreferences("diagnostics",0).getLong("evicted",0))
        val directory=File(context.noBackupFilesDir,"diagnostic-outbox").apply{mkdirs()}
        synchronized(this) {
            val payload=boundedRecord(record,error).toString().toByteArray(Charsets.UTF_8)
            check(payload.size<=64*1024){"diagnostic_event_size_budget"}
            val files=directory.listFiles().orEmpty().filter{it.extension=="json"}.sortedBy{it.name}
            // 保留最新2MB/128条；明确计数被淘汰的事件，避免错误风暴撑爆存储。
            var bytes=files.sumOf{it.length()}
            for((i,file) in files.withIndex()) {
                if(files.size-i<128 && bytes+payload.size<=2*1024*1024)break
                bytes-=file.length();file.delete()
                val p=context.getSharedPreferences("diagnostics",0)
                p.edit().putLong("evicted",p.getLong("evicted",0)+1).commit()
            }
            val file=android.util.AtomicFile(File(directory,"${System.currentTimeMillis()}-${record.getString("id")}.json"))
            val out=file.startWrite()
            try{
                out.write(payload);file.finishWrite(out)
            }catch(e:Exception){file.failWrite(out);throw e}
        }
    }
    /** UTF-8字节预算同时约束异常链与系统trace，超长记录可上报且明确截断。 */
    internal fun boundedRecord(record:JSONObject,error:Throwable?):JSONObject {
        if(record.toString().toByteArray(Charsets.UTF_8).size<=60*1024)return record
        record.put("truncated",true)
        record.optJSONObject("extra")?.let { extra ->
            if(extra.has("trace"))extra.put("trace",boundedUtf8(extra.optString("trace"),16*1024)).put("traceTruncated",true)
        }
        record.put("error",JSONObject().put("class",error?.javaClass?.name)
            .put("stack",boundedUtf8(error?.stackTrace?.take(128)?.joinToString("\n").orEmpty(),16*1024)).put("truncated",true))
        if(record.toString().toByteArray(Charsets.UTF_8).size>60*1024)
            record.put("parameters",JSONObject().put("truncated",true))
        return record
    }
    internal fun boundedUtf8(value:String,maxBytes:Int):String {
        if(value.toByteArray(Charsets.UTF_8).size<=maxBytes)return value
        var low=0;var high=minOf(value.length,maxBytes)
        while(low<high){val mid=(low+high+1)/2
            if(value.substring(0,mid).toByteArray(Charsets.UTF_8).size<=maxBytes)low=mid else high=mid-1}
        return value.substring(0,low)
    }
    fun send() {
        if(!sending.compareAndSet(false,true))return
        try{worker.execute{try{flush()}finally{sending.set(false)}}}catch(_:RejectedExecutionException){sending.set(false)}
    }
    private fun flush() {
        val context=app?:return
        val directory=File(context.noBackupFilesDir,"diagnostic-outbox")
        for(file in directory.listFiles().orEmpty().filter{it.extension=="json"}.sortedBy{it.name}.take(16)) {
            val result=runCatching{context.contentResolver.call(Uri.parse("content://com.ecarx.autoservice.diagnostics"),
                "append",null,Bundle().apply{putString("event",file.readText())})}.getOrNull()
            if(result?.getBoolean("accepted")==true)file.delete() else { scheduleRetry(context);break }
        }
        if(directory.listFiles().orEmpty().any{it.extension=="json"})scheduleRetry(context)
    }
    private fun scheduleRetry(context:Context) {
        if(!retryScheduled.compareAndSet(false,true))return
        // 不唤醒设备、不增加常驻线程；App还活着时，临时缺失的中继恢复后可重传。
        Handler(context.mainLooper).postDelayed({retryScheduled.set(false);send()},60_000)
    }
    private fun recover() {
        val context=app?:return
        if(Build.VERSION.SDK_INT<30)return
        val prefs=context.getSharedPreferences("diagnostics",0)
        var last=prefs.getLong("lastExit",0)
        val exits=context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName,0,16)
            .filter{it.timestamp>last}.sortedBy{it.timestamp}
        exits.forEach{exit->
            if(exit.reason in setOf(2,3,4,5,6,7,8,9)) {
                val prior=previousRun?.takeIf{it.optInt("pid")==exit.pid && it.optLong("at")<=exit.timestamp}
                val extra=JSONObject().put("exitAt",exit.timestamp).put("reason",exit.reason).put("status",exit.status)
                    .put("crashedApkSha256",prior?.optString("apkSha256")?:"unknown")
                    .put("crashedVersion",prior?.optString("version")?:"unknown")
                    .put("previousPid",exit.pid).put("pssKb",exit.pss).put("rssKb",exit.rss)
                // 系统trace可能含任意业务载荷；仅采集脱敏后的源码/原生栈行，不读录像/车辆侧录。
                val trace=runCatching{exit.traceInputStream?.bufferedReader()?.use { reader ->
                    reader.lineSequence().take(1200).filter { it.trim().startsWith("at ") || it.contains("backtrace:") ||
                        it.trim().matches(Regex("#\\d+.*")) || it.startsWith("signal ") }.take(256).joinToString("\n"){safe(it)}
                }}.getOrNull()
                extra.put("trace",trace?.let{boundedUtf8(it,32*1024)}?:"not_available").put("traceAvailable",!trace.isNullOrBlank())
                    .put("traceTruncated",trace!=null && trace.toByteArray(Charsets.UTF_8).size>32*1024)
                persist("recovered_exit","previous_process_exit",null,emptyMap(),extra)
            }
            last=maxOf(last,exit.timestamp);check(prefs.edit().putLong("lastExit",last).commit())
        }
    }
    internal fun safe(raw:String):String = raw.take(8192)
        .replace(Regex("(?i)(bearer\\s+|(?:token|secret|password|key|authorization)\\s*[:=]\\s*)[^\\s,;]+"),"$1[redacted]")
        .replace(Regex("https?://[^\\s]+"),"[url]")
        .replace(Regex("/[A-Za-z0-9_./%-]+")) { match ->
            val base=match.value.substringAfterLast('/')
            if(base.matches(Regex("lib[A-Za-z0-9_.-]+\\.so"))) "[library]$base" else "[path]"
        }
        .replace(Regex("[a-fA-F0-9]{32,}"),"[identifier]")
        .replace(Regex("\\b[0-9]{7,}\\b"),"[number]")
    private fun parameters(values:Map<String,Any?>):JSONObject = JSONObject().apply {
        val numbers=setOf("width","height","fps","bitrate","gop","segmentIndex","queueBytes","timeoutMs","status","reason","api","count")
        val enums=setOf("direction","codec","implementation","transport","thread","method","caller","state","stage")
        values.entries.take(32).forEach{(k,v)->put(safe(k),when{
            k in numbers && v is Number -> v
            k in enums && v is String -> safe(v).take(256)
            else -> "[${v?.javaClass?.simpleName?:"null"}]"
        })}
    }
    private fun throwable(error:Throwable):JSONObject {
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        fun encode(t:Throwable,depth:Int):JSONObject {
            if(depth>=12 || !seen.add(t))return JSONObject().put("truncated",true)
            return JSONObject().put("class",t.javaClass.name).put("message",safe(t.message.orEmpty()))
                .put("stack",org.json.JSONArray(t.stackTrace.take(128).map{it.toString()}))
                .put("cause",t.cause?.let{encode(it,depth+1)})
                .put("suppressed",org.json.JSONArray(t.suppressed.take(4).map{encode(it,depth+1)}))
        }
        return encode(error,0)
    }
}
