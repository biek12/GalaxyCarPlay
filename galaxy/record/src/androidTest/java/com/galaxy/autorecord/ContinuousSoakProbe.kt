package com.galaxy.autorecord

import android.app.Instrumentation
import android.graphics.ImageFormat
import android.media.*
import android.os.*
import com.galaxy.autorecord.core.*
import com.galaxy.autorecord.recording.*
import com.galaxy.autorecord.storage.FinishOutcome
import org.json.JSONObject
import java.io.File
import java.util.Random
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** 仅隔离模拟器：高熵YUV→真实Reader→真实MediaCodec/MP4，真实写满1GiB后循环覆盖。 */
internal object ContinuousSoakProbe {
    fun run(instrumentation:Instrumentation, seconds:Int, report:Bundle) {
        require(seconds in 5..7200)
        val context=instrumentation.targetContext
        val root=File(context.cacheDir,"continuous-soak-${System.nanoTime()}").apply{mkdirs()}
        val settings=RecordSettings(quotaBytes=1024L*1024*1024,segmentSeconds=10,requestedHeight=720,fps=20,
            encoderImplementation=RecordSettings.IMPL_SOFTWARE,minFreeBytes=64L*1024*1024,eventProtect=true)
        val info=CodecCapabilities.encoders(RecordSettings.CODEC_H264).first{it.name=="OMX.google.h264.encoder"}
        report.putString("encoderPlan","${info.name} bitrateRange=${info.bitrateRange} requested=12000000, 1280x720x20 moving 2x2 blocks: Y4bit neutral UV")
        report.putString("artifacts",root.path)
        val clock=SegmentClock(System.currentTimeMillis(),SystemClock.elapsedRealtimeNanos()/1000,settings.segmentSeconds)
        val failure=AtomicReference<Exception?>()
        var completed=0;var committedFrames=0L;var lastPts=-1L;var maxGapUs=0L;var locked:File?=null
        val samples=File(root,"resources.jsonl").bufferedWriter()
        val frameThread=HandlerThread("soak-frames").apply{start()}
        lateinit var writer:ContinuousSegmentWriter
        val codec=ContinuousCodec("front",info,1280,720,settings.fps,12_000_000,1,
            {writer.format("front",it)},{data,packet->writer.sample("front",data,packet)},{failure.compareAndSet(null,it)})
        val listener=object:RecordingEngineListener {
            override fun onSegmentStarted(index:Int,segmentDir:File){}
            override fun onRecordingActive(){}
            override fun onProgress(bytesWritten:Long,segmentElapsedMs:Long,segmentIndex:Int){}
            override fun onStopped(error:String?){}
            override fun onSegmentFinished(index:Int,outcome:FinishOutcome){
                check(outcome.manifestWritten && outcome.completedDirections==listOf("front")){"segment_failed:$index"}
                val video=SegmentLayout.videoFile(outcome.segmentDir,"front")
                val extractor=MediaExtractor()
                try {
                    extractor.setDataSource(video.path);extractor.selectTrack(0)
                    check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0){"segment_not_independent"}
                    var previous=-1L;var count=0
                    while(extractor.sampleTime>=0){check(extractor.sampleTime>previous);previous=extractor.sampleTime;count++;extractor.advance()}
                    check(count>0);committedFrames+=count
                }finally{extractor.release()}
                val manifest=SegmentManifest.fromJson(File(outcome.segmentDir,SegmentLayout.FILE_MANIFEST).readText())!!
                val channel=manifest.channels.first{it.direction=="front"}
                val first=manifest.startedAtMonotonicNanos/1000+channel.firstPtsUs
                val last=manifest.startedAtMonotonicNanos/1000+channel.lastPtsUs
                if(lastPts>=0){check(first>lastPts){"duplicate_or_backward_source_pts"};maxGapUs=maxOf(maxGapUs,first-lastPts)}
                lastPts=last;completed++
                if(locked==null){check(SegmentMarks(SegmentLayout.indexDir(root)).setLocked(listOf(outcome.segmentDir.name),true)!=null);locked=outcome.segmentDir}
            }
        }
        writer=ContinuousSegmentWriter(root,settings,clock,mapOf("front" to codec),OfflineVehicleFeed("合成测试，未连接车辆"),"soak",listener,{failure.compareAndSet(null,it)})
        val reader=RecordingFrameReader(1280,720,Handler(frameThread.looper),{codec.queue(it)},{failure.compareAndSet(null,it)})
        val producer=ImageWriter.newInstance(reader.surface,3,ImageFormat.YUV_420_888)
        val random=Random(733);val y=ByteArray(1280*720);val uv=ByteArray(1280*720/4)
        var producerFrames=0L;var peakBytes=0L;var nextSync=clock.firstEndUs;var nextMetric=0L
        val started=SystemClock.elapsedRealtime();var nextFrame=SystemClock.elapsedRealtimeNanos()
        var finishOk=false
        try{
            writer.initialize();codec.scheduleKeyFrames(clock);codec.start()
            while(SystemClock.elapsedRealtime()-started<seconds*1000L){
                failure.get()?.let{throw it}
                val now=SystemClock.elapsedRealtimeNanos()
                if(now/1000>=nextSync){codec.requestKeyFrame();nextSync+=clock.periodUs}
                val image=producer.dequeueInputImage()
                try{
                    // 每帧重新随机的2x2亮度块，避免此软件编码器固定单帧输出缓冲被纯白噪声撑爆。
                    // 保持高时间熵与真实编码写盘；色度中性，不用文件填充或重复复制凑配额。
                    for(row in 0 until 720 step 2) for(col in 0 until 1280 step 2){
                        val value=(random.nextInt(16)*16+8).toByte();val at=row*1280+col
                        y[at]=value;y[at+1]=value;y[at+1280]=value;y[at+1281]=value
                    }
                    uv.fill(128.toByte())
                    image.planes.forEachIndexed{i,plane->
                        val bytes=if(i==0)y else uv
                        val width=if(i==0)1280 else 640;val height=if(i==0)720 else 360
                        val buffer=plane.buffer
                        for(row in 0 until height){
                            if(plane.pixelStride==1){buffer.position(row*plane.rowStride);buffer.put(bytes,row*width,width)}
                            else for(col in 0 until width)buffer.put(row*plane.rowStride+col*plane.pixelStride,bytes[row*width+col])
                        }
                    }
                    image.timestamp=SystemClock.elapsedRealtimeNanos()
                    producer.queueInputImage(image);producerFrames++
                }catch(e:Exception){image.close();throw e}
                nextFrame+=50_000_000
                val remaining=(nextFrame-SystemClock.elapsedRealtimeNanos())/1_000_000
                if(remaining>0)Thread.sleep(remaining)else nextFrame=SystemClock.elapsedRealtimeNanos()
                if(SystemClock.elapsedRealtime()>=nextMetric){
                    val bytes=SegmentLayout.segmentsRoot(root).walkTopDown().filter{it.isFile}.sumOf{it.length()}
                    peakBytes=maxOf(peakBytes,bytes)
                    val mem=Debug.MemoryInfo().also{Debug.getMemoryInfo(it)}
                    samples.write(JSONObject().put("atMs",SystemClock.elapsedRealtime()-started).put("cpuMs",Process.getElapsedCpuTime())
                        .put("pssKb",mem.totalPss).put("threads",File("/proc/self/task").list()?.size)
                        .put("fds",File("/proc/self/fd").list()?.size).put("diskBytes",bytes)
                        .put("encodedBytes",writer.bytesWritten).put("queuePeakBytes",writer.highWaterBytes)
                        .put("rotations",writer.rotations).put("producerFrames",producerFrames)
                        .put("codecInput",codec.inputFrames.get()).put("codecOutput",codec.outputFrames.get())
                        .put("backpressure",codec.backpressure.get()).put("inputWaits",codec.inputWaits.get()).toString()+"\n");samples.flush()
                    nextMetric=SystemClock.elapsedRealtime()+15_000
                }
            }
            producer.close();reader.drainAndClose()
            check(codec.finish());writer.finish(null);finishOk=true
            failure.get()?.let{throw it}
            check(producerFrames==codec.inputFrames.get() && codec.inputFrames.get()==codec.outputFrames.get()) {"source_or_codec_frame_loss"}
            check(committedFrames==codec.outputFrames.get()) {"muxer_frame_loss"}
            check(codec.backpressure.get()==0L) {"input_frame_discarded"}
            if(seconds>=120) {
            check(writer.bytesWritten>1536L*1024*1024){"not_enough_real_encoded_bytes_for_1GiB_rotation:${writer.bytesWritten}"}
            check(writer.rotations>=5){"rotation_not_exercised"}
            check(locked?.isDirectory==true){"protected_segment_deleted"}
            }
            check(SegmentLayout.segmentsRoot(root).walkTopDown().none{it.name.endsWith(".writing")})
            val retained=SegmentLayout.segmentsRoot(root).walkTopDown().filter{it.isFile}.sumOf{it.length()}
            check(retained<=settings.quotaBytes){"quota_exceeded:$retained"}
            report.putString("soak", "${if(seconds<120) "SMOKE_ONLY" else "PASS"} seconds=$seconds quota=1073741824 realEncodedBytes=${writer.bytesWritten} rotations=${writer.rotations} completed=$completed peakDiskBytes=$peakBytes retained=$retained queuePeak=${writer.highWaterBytes} maxBoundaryGapUs=$maxGapUs producer=$producerFrames input=${codec.inputFrames.get()} output=${codec.outputFrames.get()} committed=$committedFrames backpressure=${codec.backpressure.get()} inputWaits=${codec.inputWaits.get()}")
            report.putString("artifacts",root.path)
        }finally{
            if(!finishOk){codec.stopInput();runCatching{reader.stopConsuming()};runCatching{producer.close()};runCatching{reader.close()};runCatching{codec.finish()};runCatching{writer.finish("probe_interrupted")}}
            frameThread.quitSafely();samples.close()
        }
    }
}
