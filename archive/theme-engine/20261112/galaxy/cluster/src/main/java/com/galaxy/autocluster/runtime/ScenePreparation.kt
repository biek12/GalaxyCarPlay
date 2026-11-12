package com.galaxy.autocluster.runtime

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.galaxy.autocluster.theme.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal data class SceneInput(val id: String,val content: EditorContent)
internal class ResourceOwner(val resources: ThemeResources) {
    private val references=AtomicInteger(1)
    fun lease(): ResourceLease { check(references.incrementAndGet()>1);return ResourceLease(this) }
    fun release(){if(references.decrementAndGet()==0)resources.close()}
}
internal class ResourceLease(private val owner: ResourceOwner) : Closeable {
    private val closed=AtomicBoolean()
    val resources get()=owner.resources
    fun fork(): ResourceLease { check(!closed.get());return owner.lease() }
    override fun close(){if(closed.compareAndSet(false,true))owner.release()}
}
internal data class PreparedScene(val serial: Long,val id: String,val content: EditorContent,val day: CompiledScene,val night: CompiledScene,val lease: ResourceLease) : Closeable {
    override fun close()=lease.close()
}

/** 准备IO与绘制分离。拖动只编译最新几何，资源集合未变时复用图片、字体和解析属性。 */
internal class ScenePreparation(context: Context,private val ready: (PreparedScene)->Unit,private val failed: (Long,Exception)->Unit) : Closeable {
    private val cacheDir=context.cacheDir
    private val thread=HandlerThread("Cluster-theme-io").apply { start() }
    private val io=Handler(thread.looper)
    private val sequence=AtomicLong()
    private val lock=Any()
    private var pending: Pair<Long,SceneInput>?=null
    private var queued=false
    @Volatile private var closed=false
    private var owner: ResourceOwner?=null
    private var assetIdentity: Map<String,ByteArray>?=null
    private var referencedAssets=emptySet<String>()
    private val propertyCache=ScenePropertyCache()
    private val latestIo=mutableMapOf<String,Runnable>()

    fun latest()=sequence.get()
    fun submit(input: SceneInput): Long {
        val serial=sequence.incrementAndGet()
        synchronized(lock){
            if(closed)return serial
            pending=serial to input
            if(!queued){queued=true;io.post(prepare)}
        }
        return serial
    }
    private val prepare=Runnable {
        val request=synchronized(lock){queued=false;pending.also { pending=null }} ?: return@Runnable
        if(closed)return@Runnable
        val (serial,input)=request
        try {
            val scene=input.content.scene
            val assets=input.content.assets
            val needed=buildSet {
                add(scene.document.dayBackground.image);add(scene.document.nightBackground.image)
                for(node in SceneTree.flatten(scene.document.nodes))for(values in listOf(node.properties,node.day,node.night)){
                    add(values["image"].orEmpty());add(values["fontAsset"].orEmpty())
                }
                for(values in listOf(scene.styles.shared,scene.styles.day,scene.styles.night)){
                    add(values["image"].orEmpty());add(values["fontAsset"].orEmpty())
                }
                remove("")
            }
            if(owner==null||assetIdentity!==assets||needed!=referencedAssets){
                val theme=SceneXml.write(ThemeMetadata(input.id,"场景资源",1),scene,assets)
                val next=ResourceOwner(ThemeResources(theme,scene,cacheDir))
                owner?.release();owner=next;assetIdentity=assets;referencedAssets=needed;propertyCache.clear()
            }
            val current=checkNotNull(owner)
            val day=CompiledScene(scene,false,current.resources,propertyCache)
            val night=CompiledScene(scene,true,current.resources,propertyCache)
            val result=PreparedScene(serial,input.id,input.content,day,night,current.lease())
            if(closed||serial!=sequence.get())result.close()else ready(result)
        }catch(error: Exception){if(!closed)failed(serial,error)}
    }
    fun postIo(action: ()->Unit){io.post { if(!closed)action() }}
    fun postLatestIo(key: String,action: ()->Unit){
        synchronized(lock){
            if(closed)return
            latestIo.remove(key)?.let(io::removeCallbacks)
            val task=object : Runnable {
                override fun run(){
                    val accepted=synchronized(lock){if(latestIo[key]!==this)false else {latestIo.remove(key);!closed}}
                    if(accepted)action()
                }
            }
            latestIo[key]=task;io.post(task)
        }
    }
    override fun close(){
        synchronized(lock){if(closed)return;closed=true;pending=null;sequence.incrementAndGet();latestIo.values.forEach(io::removeCallbacks);latestIo.clear()}
        io.post { owner?.release();owner=null;propertyCache.clear();thread.quitSafely() }
    }
}
