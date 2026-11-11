package com.ecarx.autoservice.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.galaxy.auto.contract.MediaIdentity

/** 系统媒体会话按系统优先级观察，不读取通知内容、不控制播放，不回采CarPlay桥自身。 */
internal class NativeMediaSource(private val context: Context,private val receive: (Bundle?,android.graphics.Bitmap?)->Unit):AutoCloseable {
    private val manager=context.getSystemService(MediaSessionManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private val component=ComponentName(context,MediaAccessListener::class.java)
    private val callbacks=linkedMapOf<MediaController,MediaController.Callback>()
    private var controllers=emptyList<MediaController>()
    private var enabled=false
    private var observing=false
    private var closed=false
    private val changed=MediaSessionManager.OnActiveSessionsChangedListener{sessions->replace(sessions.orEmpty())}
    fun enabled(value: Boolean){
        enabled=value
        if(!value){if(observing){manager.removeOnActiveSessionsChangedListener(changed);observing=false;replace(emptyList())};return}
        if(observing||closed)return
        val granted=context.getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        if(!granted)return
        if(!observing&&!closed)try{manager.addOnActiveSessionsChangedListener(changed,component,main);observing=true;replace(manager.getActiveSessions(component))}catch(error:SecurityException){com.ecarx.autoservice.ServiceTrace.event("media_session_access",error)}
    }
    private fun replace(next:List<MediaController>){
        callbacks.forEach{(controller,callback)->controller.unregisterCallback(callback)};callbacks.clear()
        controllers=next
        next.forEach{controller->
            val callback=object:MediaController.Callback(){
                override fun onMetadataChanged(metadata:MediaMetadata?){publish()}
                override fun onPlaybackStateChanged(state:PlaybackState?){publish()}
                override fun onSessionDestroyed(){replace(runCatching{manager.getActiveSessions(component)}.getOrDefault(emptyList()))}
            }
            callbacks[controller]=callback;controller.registerCallback(callback,main)
        }
        publish()
    }
    private fun publish(){
        if(closed||!enabled)return
        val source=controllers.firstOrNull{it.playbackState?.state==PlaybackState.STATE_PLAYING}
            ?:controllers.firstOrNull{it.playbackState?.state==PlaybackState.STATE_PAUSED}
        if(source==null||source.packageName in setOf(context.packageName,"com.ecarx.carplay","com.galaxy.autocluster")){receive(null,null);return}
        val metadata=source.metadata?:run{receive(null,null);return}
        val playback=source.playbackState
        val now=SystemClock.elapsedRealtime();val playing=playback?.state==PlaybackState.STATE_PLAYING
        val title=metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?:metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val artist=metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty();val album=metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val id=metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val app=runCatching{context.packageManager.getApplicationInfo(source.packageName,0).loadLabel(context.packageManager).toString()}.getOrDefault(source.packageName)
        val value=Bundle().apply{
            putString("sourceKind","native");putString("sourceApp",app);putString("sourcePackage",source.packageName);putString("sourceTrackId",id)
            putString("title",title);putString("artist",artist);putString("album",album)
            putString("track",MediaIdentity.key("native",source.packageName,title,artist,album,id))
            putBoolean("playing",playing);putLong("sampledAt",now)
            val age=if(playing&&playback!=null&&playback.lastPositionUpdateTime>0)(now-playback.lastPositionUpdateTime).coerceAtLeast(0)else 0
            putLong("elapsed",(playback?.position?:-1).let{if(it<0)-1 else it+(age*(playback?.playbackSpeed?:1f)).toLong()})
            putLong("duration",if(metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION))metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)else -1)
        }
        receive(value,metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?:metadata.getBitmap(MediaMetadata.METADATA_KEY_ART))
    }
    override fun close(){closed=true;if(observing)manager.removeOnActiveSessionsChangedListener(changed);callbacks.forEach{(c,cb)->c.unregisterCallback(cb)};callbacks.clear();controllers=emptyList()}
}

/** 权限仅用于媒体会话发现，禁止处理或转发任何通知正文。 */
class MediaAccessListener:NotificationListenerService()
