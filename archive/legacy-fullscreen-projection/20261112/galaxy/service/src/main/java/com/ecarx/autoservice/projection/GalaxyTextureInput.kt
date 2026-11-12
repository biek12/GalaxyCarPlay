package com.ecarx.autoservice.projection

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface
import java.io.Closeable

/** GL线程拥有纹理/ST/Surface；生产者只借用Surface，updateTexImage只取最新缓冲。 */
internal class GalaxyTextureInput(width: Int,height: Int,handler: Handler,changed: () -> Unit) : Closeable {
    val texture: Int
    val surfaceTexture: SurfaceTexture
    val surface: Surface
    val matrix = FloatArray(16)
    var pending = false; private set
    var hasFrame = false; private set
    var frames = 0L; private set
    init {
        val names=IntArray(1);GLES20.glGenTextures(1,names,0);texture=names[0]
        check(texture!=0) { "gl_texture_failed" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture=SurfaceTexture(texture)
        surfaceTexture.setDefaultBufferSize(width,height)
        surface=Surface(surfaceTexture)
        surfaceTexture.setOnFrameAvailableListener({ pending=true;changed() },handler)
    }
    fun latch(): Boolean {
        if(!pending)return false
        pending=false;surfaceTexture.updateTexImage();surfaceTexture.getTransformMatrix(matrix);hasFrame=true;++frames
        return true
    }
    override fun close() {
        surfaceTexture.setOnFrameAvailableListener(null)
        surface.release();surfaceTexture.release();GLES20.glDeleteTextures(1,intArrayOf(texture),0)
    }
}
