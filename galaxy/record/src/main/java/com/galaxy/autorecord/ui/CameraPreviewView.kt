package com.galaxy.autorecord.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Gravity
import android.widget.FrameLayout
import com.galaxy.autorecord.core.CameraProfile

/** 页面拥有 Surface；销毁时按引用撤销，旧格子的回调不能解绑新格子。 */
internal class CameraPreviewView(private val host: RecordActivity, private val direction: String) : FrameLayout(host), SurfaceHolder.Callback {
    private var output: Surface? = null
    private val preview = SurfaceView(host)
    init {
        addView(preview, LayoutParams(-1, -1, Gravity.CENTER))
        preview.holder.setFixedSize(CameraProfile.JPEG_WIDTH, CameraProfile.JPEG_HEIGHT)
        preview.holder.addCallback(this)
    }
    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // holder 拥有 Surface；本页与采集器均不主动 release，避免把系统的新 Surface 关闭。
        if (output === holder.surface) return
        output?.let { host.unbindPreviewSurface(direction, it) }
        output = holder.surface.also { host.bindPreviewSurface(direction, it) }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        output?.let { host.unbindPreviewSurface(direction, it) }
        output = null
    }
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width,height,oldWidth,oldHeight)
        val scale = minOf(width.toFloat()/CameraProfile.JPEG_WIDTH, height.toFloat()/CameraProfile.JPEG_HEIGHT)
        preview.layoutParams = LayoutParams((scale*CameraProfile.JPEG_WIDTH).toInt(), (scale*CameraProfile.JPEG_HEIGHT).toInt(), Gravity.CENTER)
    }
}
