package com.galaxy.autorecord

import android.app.Instrumentation
import android.os.Bundle
import android.view.Surface
import com.galaxy.autorecord.core.*
import com.galaxy.autorecord.recording.RecordingEngine
import com.galaxy.autorecord.storage.FinishOutcome
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 实际Reader/codec，但生产端明确不产帧：验证超时错误、关闭回执与退出，不把无画面当录制中。 */
internal object RecordStallProbe {
 fun run(test:Instrumentation,report:Bundle) {
  val root=File(test.targetContext.cacheDir,"stall-probe-${System.nanoTime()}").apply{mkdirs()}
  val volumes=object:StorageVolumes {
   override fun list()=listOf(VolumeInfo(null,"隔离故障注入",false,true,root,root.usableSpace,root.totalSpace,true))
   override fun resolve(uuid:String?)=list().single()
   override fun probeWritable(dir:File)=true
  }
  val detached=CountDownLatch(1);val stopped=CountDownLatch(1);val failure=AtomicReference<String?>()
  val settings=RecordSettings(requestedHeight=720,encoderImplementation=RecordSettings.IMPL_SOFTWARE,minFreeBytes=1024*1024)
  val codec=CodecCapabilities.encoders(RecordSettings.CODEC_H264).first{it.name=="OMX.google.h264.encoder"}
  val engine=RecordingEngine(volumes,OfflineVehicleFeed("测试，不连接车辆"),object:RecordSurfaceBinder {
   override fun bindRecordSurface(direction:String,surface:Surface?,previous:Surface?) {}
   override fun detachRecordSurfaces(surfaces:Map<String,Surface>,completed:(Result<Unit>)->Unit) {detached.countDown();completed(Result.success(Unit))}
  },{settings},"stall-probe",encoderPicker={codec})
  try {
   engine.start(listOf("front"),settings,object:RecordingEngineListener {
    override fun onSegmentStarted(index:Int,segmentDir:File){}
    override fun onRecordingActive(){error("no_source_must_not_claim_recording")}
    override fun onSegmentFinished(index:Int,outcome:FinishOutcome){}
    override fun onProgress(bytesWritten:Long,segmentElapsedMs:Long,segmentIndex:Int){}
    override fun onStopped(error:String?){failure.set(error);stopped.countDown()}
   })
   check(stopped.await(20,TimeUnit.SECONDS)){"stalled_pipeline_did_not_stop"}
   check(failure.get()?.contains("编码输出超时")==true){"missing_stall_error:${failure.get()}"}
   check(detached.count==0L){"producer_detach_not_requested"}
   report.putString("stall","PASS: no frames, never recording-active, per-channel timeout, detach ACK, EOS/release completed")
  }finally{engine.release()}
 }
}
