package com.ecarx.autoservice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.*
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClusterElementPipelineTest {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private fun pump(condition:()->Boolean){
        repeat(300){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10));if(condition())return;Thread.sleep(10)}
        fail("worker did not complete")
    }
    @Test fun imageFdToFrameMediaLeaseRuntimeReceiptAndRestore(){
        val input=File(app.cacheDir,"pipeline.png")
        val bitmap=Bitmap.createBitmap(2,1,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
        input.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val config=ClusterDisplayConfiguration(ClusterDisplayConfiguration.OEM_ELEMENTS,ClusterDisplayConfiguration.IMAGE_BACKGROUND,true,"pipeline.png")
        ParcelFileDescriptor.open(input,ParcelFileDescriptor.MODE_READ_ONLY).use{ClusterBackgroundStore(app).import(config.image,ParcelFileDescriptor.dup(it.fileDescriptor))}
        val raw=ClusterBackgroundStore(app).file(config.image).readBytes();assertEquals(1920*532*4,raw.size)
        // Fit contains the complete picture and adds black side bars, without cropping.
        assertArrayEquals(byteArrayOf(0,0,0,-1),raw.copyOfRange(0,4))
        assertArrayEquals(byteArrayOf(-1,0,0,-1),raw.copyOfRange((266*1920+960)*4,(266*1920+960)*4+4))
        val published=ConcurrentHashMap<String,ByteArray>();var generation=0L;var expected=0
        val publisher=KanziMediaPublisher(app,published={g,f->generation=g;expected=f},write={command,_->
            Regex("cat '([^']+)' > '[^']+/([^/']+)\\.tmp'").findAll(command).forEach{m->published[m.groupValues[2]]=File(m.groupValues[1]).readBytes()}
            JSONObject().put("exitCode",0)
        })
        val media=Bundle().apply{putString("title","Pipeline Song");putString("artist","Artist");putString("track","source:id");putLong("elapsed",1500);putLong("duration",6000);putBoolean("playing",false);putStringArrayList("lyrics",arrayListOf("Previous","Current","Next","After"));putLongArray("lyricTimes",longArrayOf(0,1000,2000,3000))}
        val state=Bundle().apply{putBundle("clusterElements",config.bundle());putBundle("media",media)}
        publisher.update(state,true);pump{generation!=0L}
        assertEquals(3,expected);assertEquals(setOf("background.bin","music.bin","hook.bin"),published.keys)
        val header=ByteBuffer.wrap(published.getValue("background.bin")).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x31464247,header.getInt(0));assertEquals(generation,header.getLong(16));assertEquals(0L,header.getLong(32));assertArrayEquals(raw,published.getValue("background.bin").copyOfRange(48,48+raw.size))
        val cache=System.getenv("GALAXY_VERIFY_CACHE")?.let{File(it,"cluster-pipeline").apply{mkdirs()}}
        published.forEach{(name,bytes)->cache?.resolve(name)?.writeBytes(bytes)}
        val starts=AtomicInteger();val stops=AtomicInteger();var receipt=ByteArray(0)
        val controller=ClusterHookController(app,{},request={op,payload,_->
            when(op){12->JSONObject().put("receipt",android.util.Base64.encodeToString(receipt,android.util.Base64.NO_WRAP))
                11->if(payload.getString("action")=="stop"){stops.incrementAndGet();JSONObject().put("state","restored")}else{starts.incrementAndGet();JSONObject().put("state","running")}
                else->error("unexpected operation")}
        })
        controller.update(true,true,config);controller.published(generation,expected)
        pump{controller.snapshot().getString("state")=="preparing"}
        assertEquals(0,starts.get()) // intent and healthy Root cannot claim a mounted UI
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4));pump{starts.get()==1}
        receipt=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(0,0x31524847);putInt(4,3);putLong(8,generation);putLong(16,System.currentTimeMillis()+3000);putInt(24,1);putInt(28,3);putLong(32,1);putLong(40,1);putInt(48,1);putInt(52,123)}.array()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));pump{controller.snapshot().getString("state")=="active"}
        assertTrue(controller.snapshot().getBoolean("backgroundMounted"));assertTrue(controller.snapshot().getBoolean("musicMounted"))
        publisher.update(Bundle(),false);controller.update(false,true,null)
        pump{stops.get()>0&&controller.snapshot().getString("state")=="restored"&&published["hook.bin"]?.let{ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getInt(4)==0}==true}
        controller.close();publisher.close()
    }
    @Test fun receiptRejectsExpiredWrongGenerationAndIntentOnly(){
        val b=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(0,0x31524847);putInt(4,3);putLong(8,4);putLong(16,12000);putInt(24,1);putInt(48,10);putInt(52,123)}
        assertNull(ClusterHookReceipt.decode(b.array(),5,1,3,10000));assertNull(ClusterHookReceipt.decode(b.array(),4,1,3,12000))
        b.putInt(4,0);assertEquals("waiting-content",ClusterHookReceipt.decode(b.array(),4,1,3,10000)?.getString("state"))
        b.putInt(48,0);assertNull(ClusterHookReceipt.decode(b.array(),4,1,3,10000))
    }
    @Test fun rootDisabledAndLateStartCannotReactivateAfterStop(){
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val stopped=AtomicInteger()
        val controller=ClusterHookController(app,{},request={op,payload,_->
            if(op==11&&payload.getString("action")=="ensure"){
                entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));JSONObject().put("state","running")
            }else if(op==11){stopped.incrementAndGet();JSONObject().put("state","restored")}
            else JSONObject().put("receipt","")
        })
        val config=ClusterDisplayConfiguration(ClusterDisplayConfiguration.OEM_ELEMENTS,ClusterDisplayConfiguration.ORIGINAL,true)
        try{
            controller.update(true,false,config);controller.published(22,1)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
            assertEquals(1L,entered.count);assertEquals("waiting-root",controller.snapshot().getString("state"))
            controller.update(true,true,config);controller.published(23,1)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4));pump{entered.count==0L}
            controller.update(false,true,null);release.countDown()
            pump{stopped.get()>0&&controller.snapshot().getString("state")=="restored"}
            assertNotEquals("active",controller.snapshot().getString("state"))
        }finally{release.countDown();controller.close()}
    }
    @Test fun invalidImageNeverCreatesReadyResource(){
        val file=File(app.cacheDir,"bad-image").apply{writeText("not an image")}
        val store=ClusterBackgroundStore(app)
        assertThrows(IllegalArgumentException::class.java){ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{store.import("bad.png",ParcelFileDescriptor.dup(it.fileDescriptor))}}
        assertFalse(store.file("bad.png").exists())
        assertThrows(IllegalArgumentException::class.java){store.file("../bad.png")}
    }
}
