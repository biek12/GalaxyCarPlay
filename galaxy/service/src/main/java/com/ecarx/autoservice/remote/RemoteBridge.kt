package com.ecarx.autoservice.remote

import android.content.Context
import android.util.AtomicFile
import com.ecarx.autoservice.platform.PlatformAccess
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** 一个会话worker；命令先落领取记录再执行，结果丢失只重传结果，绝不自动重跑Shell。 */
internal class RemoteBridge(context: Context,private val report: (String)->Unit): Closeable {
    private val app=context.applicationContext
    private val settings=RemoteSettings(app)
    private val grant=settings.grant
    private val http=RemoteHttp(app,settings)
    private val platform=PlatformAccess(app)
    private val pending=AtomicFile(File(app.noBackupFilesDir,"remote-result-$grant.json"))
    private val thread=Thread(::run,"AutoService-remote").apply{isDaemon=true}
    @Volatile private var closed=false
    fun start(){thread.start()}
    private fun run(){
        var delay=1000L
        while(!closed&&settings.enabled){
            try {
                val status=http.json("/bridge/register",JSONObject().put("id",settings.id).put("secret",settings.secret)
                    .put("grant",settings.grant).put("until",settings.until))
                check(!closed&&settings.grant==grant)
                settings.observed(grant,status.optString("code"),status.optBoolean("paired"),status.optLong("codeUntil"))
                report(if(settings.paired)"connected"else"waiting_code")
                val saved=runCatching{JSONObject(String(pending.readFully(),Charsets.UTF_8))}.getOrNull()
                if(saved!=null){
                    // 更新自身APK导致进程结束时，不猜测上一安装结果，交由电脑再次核对包摘要。
                    if(!saved.has("result"))saved.put("result",JSONObject().put("error","execution_interrupted_check_state"))
                    http.json("/bridge/result?device=${settings.id}",saved);pending.delete()
                }
                val job=http.json("/bridge/poll?device=${settings.id}",JSONObject())
                delay=1000
                if(!job.has("id"))continue
                check(settings.enabled&&!closed)
                val record=JSONObject().put("id",job.getString("id"));save(record)
                val result=runCatching{execute(job)}.getOrElse{JSONObject().put("error",it.javaClass.simpleName)}
                record.put("result",result);save(record)
                http.json("/bridge/result?device=${settings.id}",record);pending.delete()
            }catch(error: Exception){
                if(!closed){
                    com.ecarx.autoservice.ServiceTrace.event("remote_connection",error)
                    report("reconnecting");try{Thread.sleep(delay)}catch(_: InterruptedException){};delay=minOf(30000,delay*2)
                }
            }
        }
        if(!closed)report("expired")
        if(settings.grant!=grant)pending.delete()
    }
    private fun save(value: JSONObject){
        val output=pending.startWrite()
        try{output.write(value.toString().toByteArray());pending.finishWrite(output)}catch(e: Exception){pending.failWrite(output);throw e}
    }
    private fun execute(job: JSONObject): JSONObject {
        val remaining=((settings.until-System.currentTimeMillis()/1000)*1000).coerceAtMost(30000).toInt()
        val timeout=minOf(job.optInt("timeoutMillis",30000),remaining)
        check(timeout>=100&&settings.enabled&&!closed)
        return when(job.getString("kind")){
            "shell"->platform.executeShell(job.getString("command"),timeout,job.getString("transport"))
            "install"->install(job,timeout)
            else->error("unknown_remote_job")
        }
    }
    private fun install(job: JSONObject,timeout: Int): JSONObject {
        val name=job.getString("package")
        require(name in setOf("com.ecarx.autoservice","com.ecarx.carplay","com.galaxy.autocluster","com.ecarx.autoservice.test","com.galaxy.autorecord"))
        val file=File(app.cacheDir,"remote-update.apk")
        try {
            http.download(job.getString("artifact"),file)
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
            val hash=digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
            check(hash==job.getString("sha256")){"apk_digest_mismatch"}
            val archive=app.packageManager.getPackageArchiveInfo(file.path,0)
            check(archive?.packageName==name){"apk_package_mismatch"}
            check(settings.enabled&&!closed)
            // pm -r校验原签名并保留数据；不传凭据、不更换签名、不使用降级/清数据参数。
            return platform.executeShell("pm install -r '${file.absolutePath}'",timeout,"ROOT")
        }finally{file.delete()}
    }
    override fun close(){closed=true;http.close();platform.close();thread.interrupt()}
    fun revoke(){
        close()
        Thread({runCatching{RemoteHttp(app,settings).use{it.json("/bridge/revoke?device=${settings.id}",JSONObject())}}},"AutoService-remote-revoke").apply{isDaemon=true;start()}
    }
}
