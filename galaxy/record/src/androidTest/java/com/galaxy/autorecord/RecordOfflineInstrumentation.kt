package com.galaxy.autorecord

import android.app.Instrumentation
import android.os.Bundle
import android.media.*
import com.galaxy.autorecord.core.*
import java.io.File

/** 仅在独立测试 APK：合成黑帧验证编码/封装/解码，不写生产段目录、不绑定车机服务。 */
class RecordOfflineInstrumentation : Instrumentation() {
    private var implementation = RecordSettings.IMPL_SOFTWARE
    private var codecName: String? = null
    private var action = "codec"
    private var seconds = 1800
    override fun onCreate(arguments: Bundle?) {
        implementation = arguments?.getString("implementation") ?: RecordSettings.IMPL_SOFTWARE
        codecName = arguments?.getString("codecName")
        action = arguments?.getString("action") ?: "codec"
        seconds = arguments?.getString("seconds")?.toIntOrNull() ?: 1800
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        val report = Bundle()
        val outcome = runCatching {
            check(BuildConfig.EMULATOR_CAMERA) { "emulator_build_required" }
            check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
            if(action=="flat-render"){FlatRenderProbe.run(report);return@runCatching}
            if(action=="sentinel-ui"){SentinelUiProbe.run(this,report);return@runCatching}
            if(action=="recovery-session"){RecordRecoveryProbe.start(this,report);return@runCatching}
            if (action == "stall") { RecordStallProbe.run(this,report);return@runCatching }
            if (action == "diagnostic-native-crash") {
                saveCrashMarker()
                android.os.Process.sendSignal(android.os.Process.myPid(),11)
                Thread.sleep(5000);error("native_signal_did_not_exit")
            }
            if (action == "native-diagnostics") {
                val deadline=android.os.SystemClock.elapsedRealtime()+15_000
                var status: Bundle?=null
                while(android.os.SystemClock.elapsedRealtime()<deadline) {
                    com.galaxy.auto.client.diagnostics.DiagnosticEvents.send()
                    status=targetContext.contentResolver.call(android.net.Uri.parse("content://com.ecarx.autoservice.diagnostics"),"status",null,crashSelector())
                    if((status?.getInt("nativeProbeMatches") ?: 0)>0)break
                    Thread.sleep(200)
                }
                check((status?.getInt("nativeProbeMatches") ?: 0)>0){"native_trace_recovery_missing:$status"}
                report.putString("nativeDiagnostics","PASS: isolated SIGSEGV injection, recovery exit, own App/PID/time matched crash backtrace")
                return@runCatching
            }
            if (action == "diagnostic-crash") {
                saveCrashMarker()
                Thread({throw IllegalStateException("synthetic_crash_probe")},"diagnostic-crash-probe").start()
                Thread.sleep(10_000);error("expected_process_exit_missing")
            }
            if (action == "diagnostics") {
                com.galaxy.auto.client.diagnostics.DiagnosticEvents.error("diagnostic_probe",IllegalArgumentException("synthetic invalid width"),mapOf("width" to 1280,"caller" to "instrumentation"))
                val deadline=android.os.SystemClock.elapsedRealtime()+10_000
                var status: Bundle?=null
                while(android.os.SystemClock.elapsedRealtime()<deadline) {
                    com.galaxy.auto.client.diagnostics.DiagnosticEvents.send()
                    status=targetContext.contentResolver.call(android.net.Uri.parse("content://com.ecarx.autoservice.diagnostics"),"status",null,crashSelector())
                    if(status?.getBoolean("probe")==true && status.getInt("javaProbeMatches")>0 && status.getInt("exitProbeMatches")>0)break
                    Thread.sleep(200)
                }
                check(status?.getBoolean("probe")==true && status.getInt("javaProbeMatches")>0 && status.getInt("exitProbeMatches")>0){"diagnostic_crash_recovery_inbox_missing:$status"}
                report.putString("diagnostics","PASS: handled error, persisted uncaught crash, recovered system exit, signature inbox ACK")
                return@runCatching
            }
            if (action == "soak") { ContinuousSoakProbe.run(this,seconds,report);return@runCatching }
            if (action == "design") { RecordDesignProbe(this).run(report); return@runCatching }
            if (action == "ui") { RecordUiProbe(this).run(report); return@runCatching }
            if (action == "rollover") { RecordRolloverProbe.run(this, codecName ?: "OMX.google.h264.encoder", report); return@runCatching }
            report.putString("encoderInventory",MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {it.isEncoder}.joinToString("; ") {
                "${it.name}: hardware=${it.isHardwareAccelerated}, software=${it.isSoftwareOnly}, types=${it.supportedTypes.joinToString()}"
            })
            val volumes = AndroidStorageVolumes(targetContext)
            val volume = volumes.resolve(null) ?: error("internal_volume_missing")
            val dir = File(volume.appDir, "offline-probe").apply { mkdirs() }
            check(volumes.probeWritable(dir))
            val manifest = File(dir,"probe.json")
            try {
                check(AtomicFiles.writeAtomically(manifest,"old".toByteArray(), true))
                check(AtomicFiles.writeAtomically(manifest,"new".toByteArray(), true))
                check(manifest.readText()=="new")
                report.putString("storage","PASS: write/fsync/atomic replace")
                val video = File(dir, "probe.mp4")
                codecProbe(video, report)
                if(action=="flat-playback")FlatPlaybackProbe.run(video,report)
                if (action == "export") RecordExportProbe.run(targetContext, video, report)
            } finally { dir.deleteRecursively() }
        }
        outcome.exceptionOrNull()?.let { report.putString("failure", it.stackTraceToString()); finish(0,report); return }
        report.putString("result","PASS"); finish(-1,report)
    }
    private fun saveCrashMarker() {
        val marker=org.json.JSONObject().put("pid",android.os.Process.myPid()).put("at",System.currentTimeMillis())
        check(AtomicFiles.writeAtomically(File(targetContext.cacheDir,"diagnostic-crash-marker.json"),marker.toString().toByteArray()))
    }
    private fun crashSelector():Bundle {
        val marker=org.json.JSONObject(File(targetContext.cacheDir,"diagnostic-crash-marker.json").readText())
        return Bundle().apply{putInt("targetPid",marker.getInt("pid"));putLong("afterAt",marker.getLong("at"))}
    }
    private fun codecProbe(file: File, report: Bundle) {
        // 此模拟器将 Google AVC 两个分类位都报 false，必须保持“未知”。仅测试 APK 允许明确点名，
        // 绝不按名字猜软件编码器，也不把诊断选择变成产品静默回退。
        val info = codecName?.let { name -> CodecCapabilities.encoders(RecordSettings.CODEC_H264).firstOrNull {it.name==name} }
            ?: if (codecName == null) CodecCapabilities.pick(RecordSettings.CODEC_H264, implementation) else null
        checkNotNull(info) { "${implementation}_avc_missing" }
        val settings=RecordSettings(encoderImplementation=implementation,requestedHeight=720,fps=20)
        val format=CodecCapabilities.format(info,settings) ?: error("encoding_plan_rejected")
        val native=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first {it.name==info.name}
            .getCapabilitiesForType(info.mime)
        check(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in native.colorFormats)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val width=format.getInteger(MediaFormat.KEY_WIDTH); val height=format.getInteger(MediaFormat.KEY_HEIGHT)
        val codec=MediaCodec.createByCodecName(info.name)
        val muxer=MediaMuxer(file.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started=false;var muxing=false;var track=-1;var samples=0
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();started=true
            var sent=0; var eos=false;var done=false
            val out=MediaCodec.BufferInfo();val deadline=android.os.SystemClock.elapsedRealtime()+20_000
            while(!done && android.os.SystemClock.elapsedRealtime()<deadline) {
                if(!eos) {
                    val input=codec.dequeueInputBuffer(10_000)
                    if(input>=0) {
                        if(sent==8) { codec.queueInputBuffer(input,0,0,sent*50_000L,MediaCodec.BUFFER_FLAG_END_OF_STREAM);eos=true }
                        else {
                            val image=codec.getInputImage(input) ?: error("encoder_yuv_image_missing")
                            image.planes.forEachIndexed { planeIndex,plane ->
                                val w=if(planeIndex==0)width else width/2;val h=if(planeIndex==0)height else height/2
                                val buffer=plane.buffer; val offset=buffer.position()
                                for(y in 0 until h) for(x in 0 until w) buffer.put(offset+y*plane.rowStride+x*plane.pixelStride,
                                    if(planeIndex==0)(if(action=="flat-playback")16+x*219/w else 16).toByte()else 128.toByte())
                            }
                            codec.queueInputBuffer(input,0,width*height*3/2,sent*50_000L,0);sent++
                        }
                    }
                }
                val index=codec.dequeueOutputBuffer(out,10_000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { check(!muxing); track=muxer.addTrack(codec.outputFormat);muxer.start();muxing=true }
                if(index>=0) {
                    val buffer=codec.getOutputBuffer(index)!!
                    if(out.size>0 && out.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) { check(muxing);buffer.position(out.offset);buffer.limit(out.offset+out.size);muxer.writeSampleData(track,buffer,out);samples++ }
                    done=out.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    codec.releaseOutputBuffer(index,false)
                }
            }
            check(done && samples>0) { "encoder_eos_timeout" }
            report.putString("codec","PASS: ${info.name}, classification=${info.implementationLabel}, ${width}x$height, $samples samples, EOS")
        } finally {
            if(started) runCatching {codec.stop()};codec.release()
            try {if(muxing)muxer.stop()} finally {muxer.release()}
        }
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.path);check(extractor.trackCount==1);extractor.selectTrack(0)
            var previous=-1L;var count=0
            while(extractor.sampleTime>=0) {check(extractor.sampleTime>=previous);previous=extractor.sampleTime;count++;extractor.advance()}
            check(count>0);report.putString("muxer","PASS: MP4 sample timestamps ordered, $count samples")
        } finally {extractor.release()}
        val retriever=MediaMetadataRetriever()
        try {retriever.setDataSource(file.path);val bitmap=retriever.getFrameAtTime(0) ?: error("decode_failed");report.putString("decode","PASS: ${bitmap.width}x${bitmap.height}");bitmap.recycle()}
        finally {retriever.release()}
    }
}
