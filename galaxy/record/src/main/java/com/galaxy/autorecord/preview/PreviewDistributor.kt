package com.galaxy.autorecord.preview

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 相机线程上的 GPU 预览分发。一条 PRIVATE 输入服务页面/转向窗，编码输出继续由 HAL 直送。 */
class PreviewDistributor(private val handler:Handler,private val failed:(Throwable)->Unit) {
    private var display=EGL14.EGL_NO_DISPLAY
    private var context=EGL14.EGL_NO_CONTEXT
    private var config:EGLConfig?=null
    private var pbuffer=EGL14.EGL_NO_SURFACE
    private var program=0
    private val uniforms=mutableMapOf<String,Int>()
    private var position=0
    private val routes=mutableMapOf<String,Input>()
    private val vertices=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f));position(0)
    }
    private class Output(val surface:Surface,var flat:Boolean,val frame:()->Unit) {var lastFrameAt=0L;var egl:EGLSurface=EGL14.EGL_NO_SURFACE}
    private class Input(val texture:Int,val image:SurfaceTexture,val surface:Surface,val width:Int,val height:Int,var profile:LensProfile?) {
        val outputs=mutableMapOf<String,Output>();val transform=FloatArray(16);var hasFrame=false
    }
    private fun initialize() {
        if(display!=EGL14.EGL_NO_DISPLAY && program!=0)return
        if(display!=EGL14.EGL_NO_DISPLAY)release()
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0)){"egl_initialize"}
        val configs=arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
            EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
        config=checkNotNull(configs[0])
        context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        check(context!=EGL14.EGL_NO_CONTEXT){"egl_context"}
        pbuffer=EGL14.eglCreatePbufferSurface(display,config,intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
        makeCurrent(pbuffer)
        fun shader(type:Int,source:String):Int {
            val id=GLES20.glCreateShader(type);GLES20.glShaderSource(id,source);GLES20.glCompileShader(id)
            val status=IntArray(1);GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,status,0)
            if(status[0]!=1){val detail=GLES20.glGetShaderInfoLog(id);GLES20.glDeleteShader(id);error("preview_shader_compile:$detail")};return id
        }
        val vertex=shader(GLES20.GL_VERTEX_SHADER,VERTEX);val fragment=shader(GLES20.GL_FRAGMENT_SHADER,FRAGMENT)
        program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vertex);GLES20.glAttachShader(program,fragment);GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex);GLES20.glDeleteShader(fragment)
        val linked=IntArray(1);GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);check(linked[0]==1){"preview_shader_link"}
        position=GLES20.glGetAttribLocation(program,"position")
        listOf("transform","planar","lens","k","aspect","camera").forEach { uniforms[it]=GLES20.glGetUniformLocation(program,it) }
    }
    fun input(direction:String,width:Int,height:Int,profile:LensProfile?):Surface {
        routes[direction]?.let { require(it.width==width&&it.height==height);it.profile=profile;return it.surface }
        initialize();makeCurrent(pbuffer)
        val textures=IntArray(1);GLES20.glGenTextures(1,textures,0);val texture=textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        val image=SurfaceTexture(texture);image.setDefaultBufferSize(width,height)
        val input=Input(texture,image,Surface(image),width,height,profile);routes[direction]=input
        image.setOnFrameAvailableListener({runCatching { render(input) }.onFailure(failed)},handler)
        return input.surface
    }
    fun bind(direction:String,owner:String,surface:Surface?,flat:Boolean=false,frame:()->Unit={}) {
        val input=routes[direction]?:return
        val old=input.outputs[owner]
        if(old?.surface===surface){old?.flat=flat;return}
        old?.let { if(it.egl!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,it.egl) }
        if(surface==null)input.outputs.remove(owner)else input.outputs[owner]=Output(surface,flat,frame)
    }
    fun flat(value:Boolean){routes.values.forEach { input->input.outputs["page"]?.flat=value;if(input.hasFrame)render(input,false) }}
    /** 解码器释放后逐路回收；其他方向仍可继续播放。 */
    fun removeInput(direction:String) {
        val input=routes.remove(direction)?:return
        makeCurrent(pbuffer)
        input.outputs.values.forEach{if(it.egl!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,it.egl)}
        input.image.setOnFrameAvailableListener(null);input.surface.release();input.image.release()
        GLES20.glDeleteTextures(1,intArrayOf(input.texture),0)
    }
    private fun makeCurrent(surface:EGLSurface) {check(EGL14.eglMakeCurrent(display,surface,surface,context)){"egl_make_current"}}
    private fun render(input:Input,newFrame:Boolean=true) {
        makeCurrent(pbuffer)
        if(newFrame){input.image.updateTexImage();input.image.getTransformMatrix(input.transform);input.hasFrame=true}
        val failedOwners=mutableListOf<String>()
        input.outputs.forEach { (owner,out)->
            if(owner.startsWith("web")){val now=android.os.SystemClock.elapsedRealtime();if(now-out.lastFrameAt<66)return@forEach;out.lastFrameAt=now}
            if(!out.surface.isValid){failedOwners+=owner;return@forEach}
            runCatching {
                if(out.egl==EGL14.EGL_NO_SURFACE)out.egl=EGL14.eglCreateWindowSurface(display,config,out.surface,intArrayOf(EGL14.EGL_NONE),0)
                check(out.egl!=EGL14.EGL_NO_SURFACE){"egl_window"};makeCurrent(out.egl)
                val w=IntArray(1);val h=IntArray(1);EGL14.eglQuerySurface(display,out.egl,EGL14.EGL_WIDTH,w,0);EGL14.eglQuerySurface(display,out.egl,EGL14.EGL_HEIGHT,h,0)
                GLES20.glViewport(0,0,w[0],h[0]);GLES20.glUseProgram(program)
                vertices.position(0)
                GLES20.glEnableVertexAttribArray(position);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices)
                GLES20.glUniformMatrix4fv(uniforms.getValue("transform"),1,false,input.transform,0)
                val profile=input.profile
                check(!out.flat||profile!=null){"flat_lens_profile_missing"}
                GLES20.glUniform1i(uniforms.getValue("planar"),if(out.flat)1 else 0)
                if(profile!=null){
                    GLES20.glUniform4f(uniforms.getValue("lens"),profile.fx/input.width,profile.fy/input.height,profile.cx/input.width,profile.cy/input.height)
                    GLES20.glUniform4fv(uniforms.getValue("k"),1,profile.coefficients,0)
                }
                GLES20.glUniform1f(uniforms.getValue("aspect"),input.width.toFloat()/input.height)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,input.texture)
                GLES20.glUniform1i(uniforms.getValue("camera"),0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
                check(EGL14.eglSwapBuffers(display,out.egl)){"egl_swap"};out.frame()
            }.onFailure { failedOwners+=owner;failed(it) }
        }
        makeCurrent(pbuffer);failedOwners.forEach { bind(routes.entries.first { it.value===input }.key,it,null) }
    }
    /** 仅 CameraDevice.onClosed 后调用，避免 HAL 仍持有已释放的 SurfaceTexture。 */
    fun release() {
        if(display==EGL14.EGL_NO_DISPLAY)return
        runCatching { makeCurrent(pbuffer);routes.values.forEach { input->
            input.outputs.values.forEach { if(it.egl!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,it.egl) }
            input.image.setOnFrameAvailableListener(null);input.surface.release();input.image.release();GLES20.glDeleteTextures(1,intArrayOf(input.texture),0)
        };if(program!=0)GLES20.glDeleteProgram(program) }
        routes.clear();uniforms.clear();program=0;EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display,pbuffer);EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);display=EGL14.EGL_NO_DISPLAY
    }
    companion object {
        private const val VERTEX="attribute vec2 position; varying vec2 uv; void main(){gl_Position=vec4(position,0.,1.);uv=vec2((position.x+1.)*.5,(position.y+1.)*.5);}"
        // 保留原片四边；中心镜头校正向边缘连续过渡，避免固定透视视角大量裁切。
        private const val FRAGMENT="""#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES camera; uniform mat4 transform; uniform int planar;
uniform vec4 lens; uniform vec4 k; uniform float aspect; varying vec2 uv;
void main(){vec2 p=uv;if(planar==1){vec2 ray=(uv-.5)*2.*2.1445069;ray.y/=aspect;
float r=length(ray);float t=atan(r);float t2=t*t;
float d=t*(1.+t2*(k.x+t2*(k.y+t2*(k.z+t2*k.w))));
vec2 corrected=lens.zw+lens.xy*ray*(r>.000001?d/r:1.);
float edge=2.*max(abs(uv.x-.5),abs(uv.y-.5));
p=mix(uv,corrected,clamp(1.-edge*edge,0.,1.));}
if(p.x<0.||p.y<0.||p.x>1.||p.y>1.){gl_FragColor=vec4(0.,0.,0.,1.);return;}
gl_FragColor=texture2D(camera,(transform*vec4(p,0.,1.)).xy);}
"""
    }
}
