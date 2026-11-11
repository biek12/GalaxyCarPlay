package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** CarPlay只发布手机媒体和原始歌词；可视网络歌词由独立AutoCluster会话查询。 */
internal object GalaxyClusterMusicBinding {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor {Thread(it,"GalaxyPlay-cluster-art").apply{isDaemon=true}}
    private val artworkQueue=NowPlayingArtworkQueue(worker=worker,main=Executor{main.post(it)},decode=CarPlayMediaKeys::decodeArtwork,publish=::onArtworkDecoded,discard=::recycle)
    private var controller:CarPlayController?=null
    private var artworkSession:Any?=null
    private var mediaInfo=CarPlayNowPlaying()
    private var requestedArtworkId:Int?=null
    private val projectionOwners=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
    @Synchronized fun projectionDemand(owner:Any,active:Boolean){if(active)projectionOwners.add(owner)else projectionOwners.remove(owner)}
    @Synchronized fun phoneLyrics(expected:CarPlayController,info:CarPlayNowPlaying,sentence:String){
        if(controller!==expected)return
        ClusterMusicState.publishLyrics(info,if(sentence.isBlank())emptyList()else listOf(ClusterLyricLine(null,sentence)))
    }
    @Synchronized fun diagnostics():Map<String,Any?> = mapOf("enabled" to false,"projectionDemand" to projectionOwners.isNotEmpty(),"phase" to "managed_by_autocluster","pending" to false,"cacheEntries" to 0)
    @Synchronized fun attach(context:Context,next:CarPlayController){
        if(controller===next)return
        clearLocked();controller=next;artworkSession=artworkQueue.newSession()
    }
    @Synchronized fun update(expected:CarPlayController,info:CarPlayNowPlaying){
        if(controller!==expected)return
        if(ClusterMusicState.trackChanged(mediaInfo,info)){artworkSession=artworkQueue.newSession();requestedArtworkId=null}
        mediaInfo=info;ClusterMusicState.publishProductInfo(info)
    }
    @Synchronized fun submitArtwork(expected:CarPlayController,id:Int,bytes:ByteArray){
        if(controller!==expected||mediaInfo.artworkTransferId!=id||requestedArtworkId==id)return
        val session=artworkSession?:return
        requestedArtworkId=id;artworkQueue.submit(session,id,bytes)
    }
    @Synchronized fun resetSession(expected:CarPlayController){if(controller===expected)clearLocked()}
    @Synchronized fun detach(expected:CarPlayController?){if(expected!=null&&controller!==expected)return;clearLocked();controller=null}
    private fun onArtworkDecoded(owner:Any,id:Int,image:Bitmap?)=synchronized(this){
        if(image==null)return@synchronized
        if(controller==null||artworkSession!==owner||mediaInfo.artworkTransferId!=id){recycle(image);return@synchronized}
        ClusterMusicState.publishProductArtwork(mediaInfo,image)
    }
    private fun clearLocked(){artworkSession=artworkQueue.newSession();requestedArtworkId=null;mediaInfo=CarPlayNowPlaying();ClusterMusicState.publishProductInfo(mediaInfo)}
    private fun recycle(bitmap:Bitmap){if(!bitmap.isRecycled)bitmap.recycle()}
}
