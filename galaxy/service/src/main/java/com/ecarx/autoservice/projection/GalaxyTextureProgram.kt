package com.ecarx.autoservice.projection

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.MapViewport
import com.galaxy.auto.contract.MapAppearance
import com.galaxy.auto.contract.MapBackdrop
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 通用OES纹理采样；SurfaceTexture矩阵处理解码器/Canvas缓冲的坐标变换。 */
internal class GalaxyTextureProgram : Closeable {
    private val program: Int
    private val position: Int
    private val coordinates: Int
    private val transform: Int
    private val sampler: Int
    private val geometry: Int
    private val effects: Int
    private val clip: Int
    private val clipRadius: Int
    private val points=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val textureCoordinates=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f));position(0)
    }
    private val vertices=FloatArray(8)
    init {
        val vertex=shader(GLES20.GL_VERTEX_SHADER,"attribute vec4 aPosition;attribute vec2 aTex;uniform mat4 uTransform;varying vec2 vTex;varying vec2 vLocal;void main(){gl_Position=aPosition;vLocal=aTex;vTex=(uTransform*vec4(aTex,0.0,1.0)).xy;}")
        var fragment=0
        val candidate=GLES20.glCreateProgram()
        try {
            fragment=shader(GLES20.GL_FRAGMENT_SHADER,"""
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES sTexture;
                uniform vec4 uGeometry;
                uniform vec4 uEffects;
                uniform vec4 uClip;
                uniform float uClipRadius;
                varying vec2 vTex;
                varying vec2 vLocal;
                float rounded(vec2 point,vec4 box,float radius){
                    vec2 q=abs(point-(box.xy+box.zw*.5))-(box.zw*.5-vec2(radius));
                    float distance=length(max(q,vec2(0.0)))+min(max(q.x,q.y),0.0)-radius;
                    return 1.0-smoothstep(-.7,.7,distance);
                }
                void main(){
                    vec2 local=vec2(vLocal.x,1.0-vLocal.y);
                    vec2 point=uGeometry.xy+local*uGeometry.zw;
                    float mask=rounded(point,uClip,uClipRadius);
                    float edge=min(min(local.x,1.0-local.x)*uGeometry.z,min(local.y,1.0-local.y)*uGeometry.w);
                    float amount=uEffects.z>.5?1.0:1.0-smoothstep(0.0,max(1.0,uEffects.y),edge);
                    vec4 color=texture2D(sTexture,vTex);
                    if(uEffects.x>0.0&&amount>.001){
                        // 对称九点核，使用输入变换后的纹理坐标；所有采样仍在GPU上。
                        vec2 d=vec2(uEffects.x/1920.0,uEffects.x/536.0);
                        vec4 soft=color*.2;
                        soft+=(texture2D(sTexture,clamp(vTex+vec2(d.x,0.0),0.0,1.0))+texture2D(sTexture,clamp(vTex-vec2(d.x,0.0),0.0,1.0)))*.12;
                        soft+=(texture2D(sTexture,clamp(vTex+vec2(0.0,d.y),0.0,1.0))+texture2D(sTexture,clamp(vTex-vec2(0.0,d.y),0.0,1.0)))*.12;
                        soft+=(texture2D(sTexture,clamp(vTex+d,0.0,1.0))+texture2D(sTexture,clamp(vTex-d,0.0,1.0))
                            +texture2D(sTexture,clamp(vTex+vec2(d.x,-d.y),0.0,1.0))+texture2D(sTexture,clamp(vTex+vec2(-d.x,d.y),0.0,1.0)))*.08;
                        color=mix(color,soft,amount);
                    }
                    gl_FragColor=color*(mask*uEffects.w);
                }
            """.trimIndent())

            GLES20.glAttachShader(candidate,vertex);GLES20.glAttachShader(candidate,fragment);GLES20.glLinkProgram(candidate)
            val linked=IntArray(1);GLES20.glGetProgramiv(candidate,GLES20.GL_LINK_STATUS,linked,0);check(linked[0]!=0) { "gl_program_link_failed" }
            program=candidate
        } catch(error: Exception) { GLES20.glDeleteProgram(candidate);throw error }
        finally { GLES20.glDeleteShader(vertex);if(fragment!=0)GLES20.glDeleteShader(fragment) }
        position=GLES20.glGetAttribLocation(program,"aPosition");coordinates=GLES20.glGetAttribLocation(program,"aTex")
        transform=GLES20.glGetUniformLocation(program,"uTransform");sampler=GLES20.glGetUniformLocation(program,"sTexture")
        geometry=GLES20.glGetUniformLocation(program,"uGeometry");effects=GLES20.glGetUniformLocation(program,"uEffects")
        clip=GLES20.glGetUniformLocation(program,"uClip");clipRadius=GLES20.glGetUniformLocation(program,"uClipRadius")
        check(listOf(position,coordinates,transform,sampler,geometry,effects,clip,clipRadius).all { it>=0 })
    }
    fun draw(input: GalaxyTextureInput,rect: MapViewport,blend: Boolean,appearance: MapAppearance=MapAppearance(),backdrop: MapBackdrop?=null) {
        if(!input.hasFrame)return
        val left=rect.x/AutoContract.WIDTH*2-1;val right=(rect.x+rect.width)/AutoContract.WIDTH*2-1
        val top=1-rect.y/AutoContract.HEIGHT*2;val bottom=1-(rect.y+rect.height)/AutoContract.HEIGHT*2
        vertices[0]=left;vertices[1]=bottom;vertices[2]=right;vertices[3]=bottom;vertices[4]=left;vertices[5]=top;vertices[6]=right;vertices[7]=top
        points.position(0);points.put(vertices);points.position(0)
        GLES20.glUseProgram(program)
        if(blend||appearance.cornerRadius>0||backdrop!=null||appearance.opacity<1f){GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA)}else GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,input.texture)
        GLES20.glUniform4f(geometry,rect.x,rect.y,rect.width,rect.height)
        val mask=backdrop?.rect?:rect
        GLES20.glUniform4f(clip,mask.x,mask.y,mask.width,mask.height)
        GLES20.glUniform1f(clipRadius,minOf(backdrop?.radius?:appearance.cornerRadius,minOf(mask.width,mask.height)/2))
        GLES20.glUniform4f(effects,backdrop?.blurRadius?:appearance.blurRadius,appearance.edgeWidth,if(backdrop!=null||appearance.blurStyle=="uniform")1f else 0f,appearance.opacity)
        GLES20.glUniform1i(sampler,0);GLES20.glUniformMatrix4fv(transform,1,false,input.matrix,0)
        GLES20.glEnableVertexAttribArray(position);GLES20.glEnableVertexAttribArray(coordinates)
        GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,points)
        GLES20.glVertexAttribPointer(coordinates,2,GLES20.GL_FLOAT,false,0,textureCoordinates)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        GLES20.glDisableVertexAttribArray(position);GLES20.glDisableVertexAttribArray(coordinates)
        check(GLES20.glGetError()==GLES20.GL_NO_ERROR) { "gl_draw_failed" }
    }
    private fun shader(type: Int,text: String): Int {
        val shader=GLES20.glCreateShader(type);GLES20.glShaderSource(shader,text);GLES20.glCompileShader(shader)
        val status=IntArray(1);GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0)
        if(status[0]==0){GLES20.glDeleteShader(shader);error("gl_shader_compile_failed")}
        return shader
    }
    override fun close(){GLES20.glDeleteProgram(program)}
}
