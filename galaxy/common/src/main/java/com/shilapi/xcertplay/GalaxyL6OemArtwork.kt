package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract

/** CarPlay仅交付封面字节；缩放、写入身份与跨QNX路径由AutoService提供。 */
internal class GalaxyL6OemArtwork(private val app:Context,private val onReady:()->Unit={}):AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private var client:AutoClient?=null
    private var ready=false
    @Volatile private var closed=false
    @Volatile private var selected:Uri?=null
    @Volatile private var copied:Uri?=null
    private var path="-999"
    private var pending:Pair<Uri,ByteArray>?=null
    private var busy=false
    private var request=0L
    private var attempts=0
    private var notBefore=0L
    private val timeout=Runnable{++request;busy=false;flush()}
    init{main.post{if(!closed)client=AutoClient(app,AutoContract.OBSERVER,onSnapshot={ready=it.getBoolean("rootReady");flush()},onState={if(it in setOf("service_disconnected","binding_died","service_unavailable"))ready=false}).also{it.connect()}}}
    fun select(uri:Uri?){
        if(closed||selected==uri)return
        selected=uri;copied=null;attempts=0;notBefore=0
        if(uri==null){main.post{pending=null};return}
        val bytes=runCatching{app.contentResolver.openInputStream(uri)?.use{input->
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=2*1024*1024);output.write(buffer,0,n)};output.toByteArray()
        }}.getOrNull()?:return
        main.post{if(!closed&&selected==uri){pending=uri to bytes;flush()}}
    }
    fun path(uri:Uri?):String=if(uri!=null&&uri==copied)path else "-999"
    private fun flush(){
        if(closed||!ready||busy||android.os.SystemClock.elapsedRealtime()<notBefore)return
        val (uri,bytes)=pending?:return
        pending=null;busy=true;val ticket=++request;main.postDelayed(timeout,6500)
        client?.prepareOemArtwork(bytes){result->
            if(ticket!=request)return@prepareOemArtwork
            main.removeCallbacks(timeout);busy=false
            if(!closed&&selected==uri&&result.getBoolean("written")){
                val value=result.getString("path")
                if(value=="/otaupdate/autoservice-media/oem-artwork.jpg"){path=value;copied=uri;onReady()}
            }
            if(!closed&&selected==uri&&!result.getBoolean("written")&&attempts<3) {
                val delay=longArrayOf(2000,5000,10000)[attempts++]
                pending=uri to bytes;notBefore=android.os.SystemClock.elapsedRealtime()+delay
                main.postDelayed({flush()},delay)
            }
            flush()
        }
    }
    override fun close(){++request;closed=true;selected=null;copied=null;main.post{main.removeCallbacksAndMessages(null);pending=null;client?.close();client=null}}
}
