package com.ecarx.autoservice.web

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.ecarx.autoservice.remote.*
import org.json.JSONObject
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** 只读FD的按需Range隧道；不发送整个文件，不接受路径或Shell。四个有界worker独立于状态/信令。 */
internal class RangeMediaBridge(private val app:Context,private val settings:RemoteSettings):Closeable {
    private class Source(val input:ParcelFileDescriptor.AutoCloseInputStream,val size:Long){@Volatile var at=SystemClock.elapsedRealtime()}
    private val sources=ConcurrentHashMap<String,Source>()
    private val workers=mutableListOf<Pair<Thread,RemoteHttp>>()
    @Volatile private var closed=false
    @Synchronized fun register(clip:String,direction:String,fd:ParcelFileDescriptor,size:Long){
        check(!closed)
        val input=ParcelFileDescriptor.AutoCloseInputStream(fd)
        val key="$clip:$direction"
        try{
            RemoteHttp(app,settings).use{it.json("/device/v1/media-ready",JSONObject().put("clipId",clip).put("direction",direction).put("size",size))}
            sources.put(key,Source(input,size))?.input?.close()
            if(workers.none{it.first.isAlive}){
                workers.clear()
                repeat(4){index->val http=RemoteHttp(app,settings);val thread=Thread({run(http)},"AutoService-media-range-$index").apply{isDaemon=true};workers.add(thread to http);thread.start()}
            }
        }catch(error:Exception){input.close();throw error}
    }
    private fun run(http:RemoteHttp){
        try{while(!closed){
            val now=SystemClock.elapsedRealtime()
            sources.entries.filter{now-it.value.at>90000}.forEach{entry->if(sources.remove(entry.key,entry.value))runCatching{entry.value.input.close()}}
            if(sources.isEmpty())break
            try{
                val request=http.json("/device/v1/range-poll",JSONObject())
                val id=request.optString("requestId");if(id.isBlank())continue
                val source=sources[request.getString("clipId")+":"+request.getString("direction")]
                val bytes=if(source==null)ByteArray(0)else synchronized(source){
                    source.at=SystemClock.elapsedRealtime()
                    val offset=request.getLong("offset");val length=request.getInt("length")
                    require(offset>=0&&length in 1..524288&&offset+length<=source.size)
                    val data=ByteArray(length);val buffer=ByteBuffer.wrap(data)
                    while(buffer.hasRemaining()){val n=source.input.channel.read(buffer,offset+buffer.position());check(n>0){"media_source_ended"}}
                    data
                }
                require(id.matches(Regex("[a-f0-9]{32}")))
                http.upload("/device/v1/range-result?id=$id",bytes.inputStream(),bytes.size.toLong(),"POST","application/octet-stream")
            }catch(error:Exception){if(!closed)try{Thread.sleep(500)}catch(_:InterruptedException){} }
        }}finally{http.close()}
    }
    @Synchronized override fun close(){if(closed)return;closed=true;workers.forEach{it.second.close();it.first.interrupt()};workers.clear();sources.values.forEach{runCatching{it.input.close()}};sources.clear()}
}
