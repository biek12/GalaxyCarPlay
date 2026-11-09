package com.galaxy.autorecord.web

import android.content.Context
import android.os.*
import android.view.Surface
import com.galaxy.autorecord.core.*
import org.json.*
import org.webrtc.*
import java.io.Closeable

/** 四路硬件H.264 + DTLS/SRTP；摄像头/GPU与本机录像共享，信令仅走AutoService。 */
internal class WebRtcLive(private val app:Context,private val camera:SurfaceCameraSource,
    private val send:(String,JSONObject)->Unit):Closeable {
    private val thread=HandlerThread("record-webrtc").apply{start()}
    private val io=Handler(thread.looper)
    private val egl=EglBase.create()
    private val hardware:HardwareVideoEncoderFactory
    private val factory:PeerConnectionFactory
    private val captures=linkedMapOf<String,Capture>()
    private val peers=linkedMapOf<String,Peer>()
    @Volatile private var closed=false
    private var leaseAt=SystemClock.elapsedRealtime()
    private val watchdog=object:Runnable{override fun run(){
        if(closed)return
        if(SystemClock.elapsedRealtime()-leaseAt>45000){peers.values.toList().forEach{it.close()};peers.clear();prune()}
        io.postDelayed(this,1000)
    }}
    init {
        synchronized(initLock){if(!initialized){PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions());initialized=true}}
        hardware=HardwareVideoEncoderFactory(egl.eglBaseContext,false,true)
        if(!hardware.supportedCodecs.any{it.name.equals("H264",true)}){egl.release();thread.quitSafely();error("hardware_h264_unavailable")}
        val h264=object:VideoEncoderFactory{
            override fun getSupportedCodecs()=hardware.supportedCodecs.filter{it.name.equals("H264",true)}.toTypedArray()
            override fun createEncoder(info:VideoCodecInfo):VideoEncoder?=if(info.name.equals("H264",true))hardware.createEncoder(info)else null
        }
        factory=PeerConnectionFactory.builder().setVideoEncoderFactory(h264).setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
        io.post(watchdog)
    }
    fun update(sessions:JSONArray){
        val copy=JSONArray(sessions.toString())
        io.post{
            if(closed)return@post
            leaseAt=SystemClock.elapsedRealtime()
            val wanted=mutableSetOf<String>()
            for(i in 0 until copy.length()){
                val value=copy.getJSONObject(i);val id=value.getString("sessionId")
                if(value.optLong("expiresAt")<=System.currentTimeMillis())continue
                wanted.add(id)
                runCatching{(peers[id]?:Peer(id,value).also{peers[id]=it}).update(value)}.onFailure{
                    android.util.Log.e("AutoRecordWeb","WebRTC session failed",it)
                    RecordDiagnostics.event("webrtc_session",it)
                    peers.remove(id)?.close()
                    send("record-live-status",JSONObject().put("sessionId",id).put("channels",JSONArray(SegmentLayout.DIRECTIONS.map{d->JSONObject().put("direction",d).put("status","unavailable")})))
                }
            }
            peers.keys.filter{it !in wanted}.toList().forEach{peers.remove(it)?.close()}
            prune()
        }
    }
    private fun capture(direction:String,flat:Boolean):Capture {
        val key=direction+if(flat)"-flat" else ""
        return captures.getOrPut(key){Capture(direction,flat)}
    }
    private fun prune(){
        val used=peers.values.flatMap{it.keys.values}.toSet()
        captures.keys.filter{it !in used}.toList().forEach{captures.remove(it)?.close()}
    }
    private inner class Capture(val direction:String,val flat:Boolean):Closeable {
        val helper=SurfaceTextureHelper.create("rtc-$direction-$flat",egl.eglBaseContext)
        val source=factory.createVideoSource(false)
        val track=factory.createVideoTrack(direction+if(flat)"-flat" else "",source)
        val surface:Surface
        init {
            check(!flat||camera.flatAvailable(direction)){"lens_profile_missing"}
            helper.setTextureSize(1280,800);surface=Surface(helper.surfaceTexture)
            source.adaptOutputFormat(1280,800,15)
            source.capturerObserver.onCapturerStarted(true)
            helper.startListening{frame->source.capturerObserver.onFrameCaptured(frame)}
            camera.bindWeb(direction,surface,flat)
        }
        override fun close(){close{}}
        fun close(done:()->Unit){camera.detachWeb(direction,surface,flat){
            helper.stopListening();source.capturerObserver.onCapturerStopped();surface.release();track.dispose();source.dispose();helper.dispose();done()
        }}
    }
    private inner class Peer(val id:String,value:JSONObject):Closeable {
        val keys=linkedMapOf<String,String>()
        private val senders=linkedMapOf<String,RtpSender>()
        private val seen=mutableSetOf<String>()
        private val pendingIce=mutableListOf<IceCandidate>()
        private var remoteReady=false
        private var offered=false
        private var statsAt=0L
        private var disposed=false
        private val pc:PeerConnection
        init {
            val ice=value.optJSONArray("iceServers")?:JSONArray()
            val servers=(0 until ice.length()).map{n->val row=ice.getJSONObject(n)
                val urls=row.getJSONArray("urls");PeerConnection.IceServer.builder((0 until urls.length()).map{urls.getString(it)})
                    .setUsername(row.optString("username")).setPassword(row.optString("credential")).createIceServer()}
            check(servers.isNotEmpty()){"turn_configuration_missing"}
            val config=PeerConnection.RTCConfiguration(servers).apply{sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY}
            pc=checkNotNull(factory.createPeerConnection(config,object:PeerConnection.Observer{
                override fun onIceCandidate(candidate:IceCandidate){
                    if(!closed&&!disposed)send("record-signals",JSONObject().put("sessionId",id).put("type","ice").put("data",JSONObject().put("candidate",candidate.sdp).put("sdpMid",candidate.sdpMid).put("sdpMLineIndex",candidate.sdpMLineIndex).toString()))
                }
                override fun onSignalingChange(state:PeerConnection.SignalingState){}
                override fun onIceConnectionChange(state:PeerConnection.IceConnectionState){android.util.Log.i("AutoRecordWeb","ICE state=$state")}
                override fun onConnectionChange(state:PeerConnection.PeerConnectionState){android.util.Log.i("AutoRecordWeb","RTC state=$state")}
                override fun onIceConnectionReceivingChange(receiving:Boolean){}
                override fun onIceGatheringChange(state:PeerConnection.IceGatheringState){}
                override fun onIceCandidatesRemoved(candidates:Array<IceCandidate>){}
                override fun onAddStream(stream:MediaStream){}
                override fun onRemoveStream(stream:MediaStream){}
                override fun onDataChannel(channel:DataChannel){channel.close()}
                override fun onRenegotiationNeeded(){}
            }))
            try{SegmentLayout.DIRECTIONS.forEach{direction->
                val flat=value.optJSONObject("flat")?.optBoolean(direction)?:false
                val track=capture(direction,flat).track
                keys[direction]=direction+if(flat)"-flat" else ""
                senders[direction]=checkNotNull(pc.addTrack(track,listOf(direction)))
            }}catch(error:Exception){pc.close();pc.dispose();throw error}
        }
        fun update(value:JSONObject){
            if(SystemClock.elapsedRealtime()-statsAt>=5000){statsAt=SystemClock.elapsedRealtime();pc.getStats{report->
                report.statsMap.values.filter{it.type=="outbound-rtp"&&it.members["kind"]=="video"}.forEach{stat->
                    val m=stat.members
                    android.util.Log.i("AutoRecordWeb","RTC encoded=${m["framesEncoded"]} sent=${m["framesSent"]} fps=${m["framesPerSecond"]} bytes=${m["bytesSent"]} encodeSeconds=${m["totalEncodeTime"]} limit=${m["qualityLimitationReason"]}")
                }
            }}
            SegmentLayout.DIRECTIONS.forEach{direction->
                val flat=value.optJSONObject("flat")?.optBoolean(direction)?:false
                val key=direction+if(flat)"-flat" else ""
                if(keys[direction]!=key){check(senders.getValue(direction).setTrack(capture(direction,flat).track,false));keys[direction]=key}
            }
            val signals=value.optJSONArray("signals")?:JSONArray()
            for(i in 0 until signals.length()){
                val signal=signals.getJSONObject(i);if(!seen.add(signal.getString("id")))continue
                val data=JSONObject(signal.getString("data"))
                when(signal.getString("type")){
                    "offer"->if(!offered){offered=true;pc.setRemoteDescription(observer({
                        remoteReady=true;pendingIce.forEach{pc.addIceCandidate(it)};pendingIce.clear()
                        pc.createAnswer(observer(created={answer->
                            pc.setLocalDescription(observer({
                                send("record-signals",JSONObject().put("sessionId",id).put("type","answer").put("data",JSONObject().put("type","answer").put("sdp",answer.description).toString()))
                                senders.values.forEach{sender->val parameters=sender.parameters;parameters.degradationPreference=RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE;parameters.encodings.forEach{it.maxBitrateBps=1_200_000;it.maxFramerate=15};sender.setParameters(parameters)}
                            }),answer)
                        }),MediaConstraints())
                    }),SessionDescription(SessionDescription.Type.OFFER,data.getString("sdp")))}
                    "ice"->{val candidate=IceCandidate(data.optString("sdpMid"),data.optInt("sdpMLineIndex"),data.getString("candidate"));if(remoteReady)pc.addIceCandidate(candidate)else if(pendingIce.size<64)pendingIce.add(candidate)}
                }
            }
        }
        private fun observer(set:()->Unit={},created:(SessionDescription)->Unit={}):SdpObserver=object:SdpObserver{
            override fun onSetSuccess(){io.post{if(!disposed&&!closed)set()}}
            override fun onCreateSuccess(description:SessionDescription){io.post{if(!disposed&&!closed)created(description)}}
            override fun onSetFailure(error:String){android.util.Log.e("AutoRecordWeb","SDP set failed: "+error.substringBefore(':').take(120));io.post{close()}}
            override fun onCreateFailure(error:String){android.util.Log.e("AutoRecordWeb","SDP create failed: "+error.substringBefore(':').take(120));io.post{close()}}
        }
        override fun close(){if(disposed)return;disposed=true;pc.close();pc.dispose();keys.clear();senders.clear()}
    }
    override fun close(){
        if(closed)return;closed=true
        io.post{io.removeCallbacksAndMessages(null);peers.values.toList().forEach{it.close()};peers.clear()
            val pending=captures.values.toList();captures.clear()
            var remaining=pending.size
            fun finished(){factory.dispose();egl.release();thread.quitSafely()}
            if(remaining==0)finished()else pending.forEach{capture->capture.close{io.post{if(--remaining==0)finished()}}}
        }
    }
    companion object {private val initLock=Any();private var initialized=false}
}
