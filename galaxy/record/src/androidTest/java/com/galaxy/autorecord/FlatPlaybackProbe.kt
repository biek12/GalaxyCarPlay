package com.galaxy.autorecord

import android.graphics.PixelFormat
import android.media.*
import android.os.*
import com.galaxy.autorecord.playback.SegmentPlayer
import com.galaxy.autorecord.preview.LensProfile
import java.io.File

internal object FlatPlaybackProbe {
    fun run(video:File,report:Bundle){
        val dir=File(video.parentFile,"flat-playback").apply{mkdirs()}
        video.copyTo(File(dir,"front.mp4"),overwrite=true)
        val extractor=MediaExtractor()
        val format=try{extractor.setDataSource(video.path);extractor.getTrackFormat(0)}finally{extractor.release()}
        val w=format.getInteger(MediaFormat.KEY_WIDTH);val h=format.getInteger(MediaFormat.KEY_HEIGHT)
        val profile=LensProfile("synthetic",w,h,w/3f,w/3f,w/2f,h/2f,floatArrayOf(0f,0f,0f,0f),"synthetic-playback-test")
        val reader=ImageReader.newInstance(320,200,PixelFormat.RGBA_8888,3)
        val errors=java.util.concurrent.LinkedBlockingQueue<String>()
        val player=SegmentPlayer(dir,listOf("front"),400,mapOf("front" to profile))
        player.onError={errors.offer(it)}
        fun pixels():IntArray{
            val deadline=SystemClock.elapsedRealtime()+10_000
            var image:Image?=null
            while(image==null&&SystemClock.elapsedRealtime()<deadline){image=reader.acquireLatestImage();if(image==null)Thread.sleep(5)}
            return checkNotNull(image){"playback_frame_missing:${errors.peek()}"}.use{img->
                val p=img.planes[0];intArrayOf(32,160,288).map{x->p.buffer.get(100*p.rowStride+x*p.pixelStride).toInt() and 255}.toIntArray()
            }
        }
        try{
            player.attach("front",reader.surface)
            val raw=pixels();val before=player.positionMs
            check(player.setFlat(true));val flat=pixels()
            check(player.positionMs==before){"mode_switch_reset_clock"}
            check(flat[0]>raw[0]+10&&raw[2]>flat[2]+10){"decoder_flat_not_applied:${raw.toList()}:${flat.toList()}"}
            check(kotlin.math.abs(raw[1]-flat[1])<=5){"decoder_center_changed"}
            player.seekTo(150);pixels();check(player.positionMs==150L)
            check(player.setFlat(false));val restored=pixels()
            check(raw.indices.all{kotlin.math.abs(raw[it]-restored[it])<6}){"decoder_raw_restore_failed"}
            check(errors.isEmpty()){"decoder_error:${errors.peek()}"}
            report.putString("flatPlayback","PASS: real MP4 -> MediaCodec -> OES/EGL, paused mode switch and 150ms seek preserve clock; synthetic gradient")
        }finally{player.release();reader.close();dir.deleteRecursively()}
    }
}
