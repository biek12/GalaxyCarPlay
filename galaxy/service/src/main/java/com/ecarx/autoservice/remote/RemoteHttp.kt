package com.ecarx.autoservice.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/** 仅出站HTTPS；优先系统已连接的蜂窝网，无蜂窝时使用默认路由，不改系统网络设置。 */
internal class RemoteHttp(context: Context,private val settings: RemoteSettings): Closeable {
    private val enrollment=File(context.noBackupFilesDir,"remote-enrollment").takeIf{it.isFile}?.readText()?.trim()
    private val networks=context.getSystemService(ConnectivityManager::class.java)
    private val current=AtomicReference<HttpURLConnection?>()
    @Volatile private var closed=false
    private fun open(path: String): HttpURLConnection {
        check(!closed)
        val url=URL(settings.origin+path)
        require(url.protocol=="https"&&url.userInfo==null)
        val cellular=networks.allNetworks.firstOrNull { network -> networks.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }==true }
        val connection=(cellular?.openConnection(url)?:url.openConnection())as HttpURLConnection
        connection.connectTimeout=10000;connection.readTimeout=30000;connection.instanceFollowRedirects=false
        connection.setRequestProperty("Authorization","Bearer ${settings.secret}")
        if(path=="/bridge/register")enrollment?.let{connection.setRequestProperty("X-Galaxy-Enrollment",it)}
        current.set(connection)
        if(closed){connection.disconnect();error("remote_closed")}
        return connection
    }
    fun json(path: String,value: JSONObject): JSONObject {
        val c=open(path)
        try {
            c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
            c.outputStream.use{it.write(value.toString().toByteArray())}
            check(c.responseCode==200){"remote_http_${c.responseCode}"}
            val bytes=c.inputStream.use{it.readBytesBounded(256*1024)}
            return JSONObject(String(bytes,Charsets.UTF_8))
        }finally{current.compareAndSet(c,null);c.disconnect()}
    }
    fun download(id: String,destination: File) {
        require(id.matches(Regex("[a-f0-9]{32,64}")))
        val c=open("/bridge/artifact?device=${settings.id}&id=$id")
        try {
            c.readTimeout=60000;check(c.responseCode==200){"artifact_http_${c.responseCode}"}
            c.inputStream.use { input->destination.outputStream().use { output ->
                val buffer=ByteArray(65536);var total=0L
                while(true){check(!closed&&settings.enabled);val count=input.read(buffer);if(count<0)break;total+=count;check(total<=128*1024*1024);output.write(buffer,0,count)}
            } }
        }finally{current.compareAndSet(c,null);c.disconnect()}
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val count=read(buffer);if(count<0)break;check(out.size()+count<=limit);out.write(buffer,0,count)}
        return out.toByteArray()
    }
    override fun close(){closed=true;current.getAndSet(null)?.disconnect()}
}
