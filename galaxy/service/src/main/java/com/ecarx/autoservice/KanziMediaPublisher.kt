package com.ecarx.autoservice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ecarx.autoservice.platform.PlatformAccess
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** AutoService独占跨系统写入。只写自有OTA桥目录，关闭不改原厂权限，租约到期恢复。 */
internal class KanziMediaPublisher(private val context: Context,private val published:(Long,Int)->Unit = {_,_->},
    private val write:((String,Int)->org.json.JSONObject)?=null): AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val worker=SerialWorker("AutoService-media-output")
    private val access=PlatformAccess(context)
    private var state=Bundle()
    private var active=false
    @Volatile private var closed=false
    private var generation=0L
    private var busy=false
    private var session=UUID(System.currentTimeMillis(),java.util.concurrent.ThreadLocalRandom.current().nextLong())
    private var hookGeneration=System.currentTimeMillis()
    private var configuration=""
    private var track=""
    private var revision=0L
    private var coverVersion=Long.MIN_VALUE
    private var imageGeneration=0L
    private val tick=object:Runnable{override fun run(){if(closed||!active)return;publish();main.postDelayed(this,1000)}}
    fun update(snapshot: Bundle,wanted: Boolean){
        state=Bundle(snapshot)
        val next=snapshot.getLong("clusterElementRevision").toString()+":"+snapshot.getBundle("clusterElements")?.let { "${it.getString("background")}:${it.getString("image")}:${it.getBoolean("music")}" }.orEmpty()
        if(next!=configuration){configuration=next;hookGeneration=maxOf(System.currentTimeMillis(),hookGeneration+1);++generation}
        if(active==wanted)return
        active=wanted;++generation;hookGeneration=maxOf(System.currentTimeMillis(),hookGeneration+1);main.removeCallbacks(tick)
        if(wanted)tick.run()else publish()
    }
    private fun publish(){
        if(closed||busy)return
        val snapshot=Bundle(state);val enabled=active;val ticket=generation
        val media=snapshot.getBundle("visualMedia")?:snapshot.getBundle("media")?:Bundle()
        val key=media.getString("track").orEmpty()
        if(key!=track){track=key;session=UUID(maxOf(System.currentTimeMillis(),session.mostSignificantBits+1),java.util.concurrent.ThreadLocalRandom.current().nextLong());revision=0;coverVersion=Long.MIN_VALUE}
        val currentSession=session;val version=snapshot.getLong("artworkVersion");val needsCover=enabled&&version!=coverVersion
        val elements=snapshot.getBundle("clusterElements")
        val musicEnabled=enabled&&elements?.getBoolean("music")==true
        val source=when(elements?.getString("background")){"image"->1;"carplay-map"->2;"local-map"->3;else->0}
        val control=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x314c4847);putInt(if(enabled)(if(musicEnabled)1 else 0) or (if(source!=0)2 else 0) else 0)
            putLong(hookGeneration);putLong(System.currentTimeMillis()+5000);putInt(source);putInt(0)
        }.array()
        val leaseGeneration=hookGeneration
        val needsImage=enabled&&source==1&&imageGeneration!=leaseGeneration
        val frame=KanziMediaFrame.encode(media,musicEnabled,currentSession,++revision,System.currentTimeMillis(),SystemClock.elapsedRealtime())
        busy=true
        worker.post{
            val result=runCatching{
                check(!closed)
                val music=File.createTempFile("cluster-music-",".bin",context.cacheDir)
                val hook=File.createTempFile("cluster-hook-",".bin",context.cacheDir).apply{writeBytes(control)}
                var cover:File?=null
                var background:File?=null
                try{
                    music.writeBytes(frame)
                    if(needsImage){
                        val raw=ClusterBackgroundStore(context).file(elements!!.getString("image")!!)
                        check(raw.isFile&&raw.length()==1920L*532*4){"background_not_prepared"}
                        val header=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
                            putInt(0x31464247);putInt(1920);putInt(532);putInt(7680)
                            putLong(leaseGeneration);putLong(1);putLong(0);putInt(1);putInt(1920*532*4)
                        }.array()
                        background=File.createTempFile("cluster-background-",".bin",context.cacheDir)
                        background!!.outputStream().use{out->out.write(header);raw.inputStream().use{it.copyTo(out)}}
                    }
                    if(needsCover){
                        val bitmap=File(context.cacheDir,"artwork.png").takeIf{it.isFile}?.let{BitmapFactory.decodeFile(it.path)}
                        if(bitmap!=null)try{
                            val small=Bitmap.createScaledBitmap(bitmap,160,160,true)
                            try{
                                val colors=IntArray(160*160);small.getPixels(colors,0,160,0,0,160,160)
                                val packet=ByteBuffer.allocate(32+colors.size*4).order(ByteOrder.LITTLE_ENDIAN)
                                packet.put(byteArrayOf(71,67,65,49));packet.putInt(160);packet.putInt(160);packet.putLong(KanziMediaFrame.coverKey(media,currentSession));packet.putInt(colors.size*4);packet.putLong(version.coerceAtLeast(1))
                                colors.forEach{packet.put((it ushr 16).toByte());packet.put((it ushr 8).toByte());packet.put(it.toByte());packet.put((it ushr 24).toByte())}
                                cover=File.createTempFile("cluster-cover-",".bin",context.cacheDir).apply{writeBytes(packet.array())}
                            }finally{if(small!==bitmap)small.recycle()}
                        }finally{bitmap.recycle()}
                    }
                    // 所有路径均为本App生成；正文只在二进制内，不进入Shell或日志。
                    val directory="/data/vendor/nfs/nfs_ota/autoservice-media"
                    fun copy(file:File,name:String)="cat '${file.absolutePath}' > '$directory/$name.tmp' && chmod 644 '$directory/$name.tmp' && mv '$directory/$name.tmp' '$directory/$name'"
                    val command="mkdir -p '$directory' && chmod 755 '$directory' && "+(background?.let{copy(it,"background.bin")+" && "}?:"")+(cover?.let{copy(it,"cover.bin")+" && "}?:"")+copy(music,"music.bin")+" && "+copy(hook,"hook.bin")
                    val response=write?.invoke(command,5000)?:access.executeShell(command,5000,"ROOT")
                    check(response.optInt("exitCode",-1)==0&&!response.optBoolean("timedOut"))
                    cover!=null
                }finally{music.delete();hook.delete();cover?.delete();background?.delete()}
            }
            main.post{busy=false;if(!closed&&ticket==generation){if(result.isSuccess){
                    if(result.getOrDefault(false))coverVersion=version
                    if(needsImage)imageGeneration=leaseGeneration
                    if(enabled)published(leaseGeneration,(if(musicEnabled&&!media.getString("title").isNullOrBlank())1 else 0) or (if(source!=0)2 else 0))
                };result.exceptionOrNull()?.let{ServiceTrace.event("cluster_media_publish",it)}};if(!closed&&ticket!=generation&&!active)publish()}
        }
    }
    override fun close(){closed=true;++generation;main.removeCallbacks(tick);access.close();worker.close()}
}
