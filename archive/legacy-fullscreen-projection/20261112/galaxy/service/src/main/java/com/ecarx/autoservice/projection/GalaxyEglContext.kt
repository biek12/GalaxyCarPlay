package com.ecarx.autoservice.projection

import android.opengl.*
import android.view.Surface
import java.io.Closeable

/** 仅GL owner线程使用；pbuffer使更新/释放输入纹理不依赖预览窗口是否仍存在。 */
internal class GalaxyEglContext : Closeable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var buffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var config: EGLConfig
    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY)
            val versions = IntArray(2)
            check(EGL14.eglInitialize(display,versions,0,versions,1)) { "egl_initialize_failed" }
            val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            val attributes = intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,EGL14.EGL_NONE)
            check(EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0) && count[0]>0) { "egl_config_failed" }
            config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            check(context != EGL14.EGL_NO_CONTEXT) { "egl_context_failed" }
            buffer = EGL14.eglCreatePbufferSurface(display,config,intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
            check(buffer != EGL14.EGL_NO_SURFACE) { "egl_pbuffer_failed" }
            current()
        } catch(error: Exception) { close(); throw error }
    }
    fun window(surface: Surface): EGLSurface = EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0).also {
        check(it != EGL14.EGL_NO_SURFACE) { "egl_window_failed" }
    }
    fun current(surface: EGLSurface = buffer) { check(EGL14.eglMakeCurrent(display,surface,surface,context)) { "egl_make_current_failed" } }
    fun swap(surface: EGLSurface) { check(EGL14.eglSwapBuffers(display,surface)) { "egl_swap_failed" } }
    fun dimensions(surface: EGLSurface): Pair<Int,Int> {
        val width=IntArray(1);val height=IntArray(1)
        check(EGL14.eglQuerySurface(display,surface,EGL14.EGL_WIDTH,width,0) && EGL14.eglQuerySurface(display,surface,EGL14.EGL_HEIGHT,height,0))
        return width[0] to height[0]
    }
    fun destroy(surface: EGLSurface?) { if(surface!=null&&surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface) }
    override fun close() {
        if(display==EGL14.EGL_NO_DISPLAY)return
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
        if(buffer!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,buffer)
        if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context)
        EGL14.eglTerminate(display);EGL14.eglReleaseThread()
        display=EGL14.EGL_NO_DISPLAY;context=EGL14.EGL_NO_CONTEXT;buffer=EGL14.EGL_NO_SURFACE
    }
}
