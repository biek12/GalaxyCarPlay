package com.galaxy.autocluster.runtime

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.lyrics.*
import com.galaxy.autocluster.BuildConfig
import java.net.HttpURLConnection

/** 一个按需可视歌词会话供两个仪表出口复用；原始媒体不被网络结果覆盖。 */
internal class VisualMusicSession(context: Context): AutoCloseable {
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private data class Cached(val at: Long,val lines: List<ClusterLyricLine>)
    private val cache=object:LinkedHashMap<String,Cached>(16,.75f,true){override fun removeEldestEntry(entry:MutableMap.MutableEntry<String,Cached>?)=size>8}
    private val lookup=GalaxyLyricsLookup(GalaxyLyricsHttpClient(open={url->
        val manager=app.getSystemService(ConnectivityManager::class.java)
        val network=manager.allNetworks.mapNotNull { n->manager.getNetworkCapabilities(n)?.takeIf{it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&!it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}?.let{n to it} }
            .maxByOrNull{(_,caps)->(if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))4 else 0)+(if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))2 else 0)}?.first
            ?:throw java.io.IOException("lyrics_network_unavailable")
        network.openConnection(url) as HttpURLConnection
    },origin=BuildConfig.GALAXY_ORIGIN))
    private lateinit var client: AutoClient
    private var latest: Bundle?=null
    private var track=""
    private var generation=0L
    private var retry: Runnable?=null
    private var closed=false
    init {client=AutoClient(app,AutoContract.VISUAL_MUSIC,onSnapshot=::snapshot,onReady={track="";latest?.let(::snapshot)}).also{it.connect()}}
    private fun snapshot(snapshot: Bundle){
        latest=Bundle(snapshot)
        val media=snapshot.getBundle("media")?:Bundle()
        val key=media.getString("track").orEmpty()
        if(!snapshot.getBoolean("clusterLyricsRequested")||key.isBlank()||media.getString("title").isNullOrBlank()){
            if(track.isNotEmpty()){cancel();track=""};return
        }
        if(key==track)return
        cancel();track=key;val ticket=++generation
        val raw=media.getStringArrayList("lyrics").orEmpty()
        if(raw.isNotEmpty()&&media.getLongArray("lyricTimes")?.size==raw.size){return}
        val cached=cache[key]?.takeIf{SystemClock.elapsedRealtime()-it.at<24*60*60*1000}
        if(cached!=null){publish(media,cached.lines);return}
        request(media,key,ticket,0)
    }
    private fun request(media: Bundle,key: String,ticket: Long,attempt: Int){
        if(closed||generation!=ticket||track!=key)return
        val query=GalaxyLyricQuery(media.getString("title").orEmpty(),media.getString("artist").orEmpty(),media.getString("sourceApp").orEmpty(),media.getString("sourceTrackId").orEmpty(),media.getString("album").orEmpty(),media.getLong("duration",-1))
        lookup.lookup(query){lines->
            if(closed||generation!=ticket||track!=key)return@lookup
            if(!lines.isNullOrEmpty()){cache[key]=Cached(SystemClock.elapsedRealtime(),lines);publish(media,lines)}
            else if(lines==null&&attempt<3){retry=Runnable{request(media,key,ticket,attempt+1)}.also{main.postDelayed(it,longArrayOf(2000,5000,10000)[attempt])}}
        }
    }
    private fun publish(media: Bundle,lines: List<ClusterLyricLine>){
        client.publishVisualMedia(Bundle(media).apply{
            putStringArrayList("lyrics",ArrayList(lines.take(AutoContract.MAX_LYRICS).map{it.text}))
            if(lines.all{it.timeMillis!=null})putLongArray("lyricTimes",lines.take(AutoContract.MAX_LYRICS).map{it.timeMillis!!}.toLongArray())else remove("lyricTimes")
        })
    }
    private fun cancel(){++generation;retry?.let(main::removeCallbacks);retry=null;lookup.cancel()}
    override fun close(){closed=true;cancel();lookup.close();client.close();cache.clear()}
}
