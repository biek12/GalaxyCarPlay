package com.galaxy.autorecord.recording

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** 每路仅此对象拥有ImageReader和借出的Image；编码器在回调结束前同步消费，不持有Image。 */
internal class RecordingFrameReader(width: Int, height: Int, handler: Handler,
    private val consume: (Image) -> Unit, onFailure: (Exception) -> Unit) : Closeable {
    private val gate = FrameReaderGate()
    private val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
    private val failed = AtomicBoolean(false)
    val surface: Surface = reader.surface

    init {
        reader.setOnImageAvailableListener({ source ->
            if (failed.get()) return@setOnImageAvailableListener
            try {
                // 录像按序消费已到达的帧；最新帧策略会在调度拥堵时主动丢弃旧图像。
                drainAvailable()
            } catch (error: Exception) {
                if (failed.compareAndSet(false, true)) onFailure(error)
            }
        }, handler)
    }

    private fun drainAvailable() {
        while(true) {
            var acquired=false
            gate.consume({ reader.acquireNextImage()?.also{acquired=true} },consume,{it.close()})
            if(!acquired)return
        }
    }

    /** 生产端已经停止后，用于消化最后的有限排队帧；资源仍由同一所有权锁保护。 */
    fun drainAndClose() { try{drainAvailable()}finally{close()} }

    /** 等待借出图像归还并拒绝新取帧；生产端尚未关闭时仍保留 Reader/Surface。 */
    fun stopConsuming() = gate.stop()

    override fun close() = gate.close {
        try { reader.setOnImageAvailableListener(null, null) } finally { reader.close() }
    }
}
