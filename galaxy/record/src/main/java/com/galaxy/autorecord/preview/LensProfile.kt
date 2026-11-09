package com.galaxy.autorecord.preview

import android.content.Context
import org.json.JSONObject
import java.io.File

/** 已核验的单路等距鱼眼标定；不以车型名/默认视角猜测镜头参数。 */
data class LensProfile(val cameraId:String,val width:Int,val height:Int,val fx:Float,val fy:Float,
    val cx:Float,val cy:Float,val coefficients:FloatArray,val source:String) {
    fun toJson():JSONObject=JSONObject().put("schema",1).put("model","opencv-fisheye4")
        .put("cameraId",cameraId).put("width",width).put("height",height)
        .put("fx",fx.toDouble()).put("fy",fy.toDouble()).put("cx",cx.toDouble()).put("cy",cy.toDouble())
        .put("k",org.json.JSONArray(coefficients.map{it.toDouble()})).put("verified",true).put("source",source)
    companion object {
        fun read(context:Context,direction:String,cameraId:String,width:Int,height:Int):LensProfile?=runCatching {
            require(direction in listOf("front","rear","left","right"))
            val file=File(context.filesDir,"lens/$direction.json")
            val raw=if(file.exists())file.readText()else context.assets.open("lens/camera-$cameraId.json").bufferedReader().use{it.readText()}
            parse(raw,cameraId,width,height)?:error("invalid_lens_profile")
        }.getOrNull()
        /** 缩放只应用于同一相机全幅等比编码；不接受裁切或不同宽高比。 */
        fun parse(raw:String,cameraId:String?=null,width:Int?=null,height:Int?=null):LensProfile?=runCatching {
            val j=JSONObject(raw)
            require(j.getInt("schema")==1 && j.getString("model")=="opencv-fisheye4")
            val id=j.getString("cameraId");val w=j.getInt("width");val h=j.getInt("height")
            require(w>0&&h>0&&(cameraId==null||id==cameraId))
            require(j.getBoolean("verified"))
            val source=j.getString("source");require(source.isNotBlank())
            val k=j.getJSONArray("k");require(k.length()==4)
            val profile=LensProfile(id,w,h,j.getDouble("fx").toFloat(),j.getDouble("fy").toFloat(),
                j.getDouble("cx").toFloat(),j.getDouble("cy").toFloat(),FloatArray(4){k.getDouble(it).toFloat()},source)
            require(listOf(profile.fx,profile.fy,profile.cx,profile.cy,*profile.coefficients.toTypedArray()).all { it.isFinite() })
            require(profile.fx>0 && profile.fy>0 && profile.cx in 0f..w.toFloat() && profile.cy in 0f..h.toFloat())
            val outW=width?:w;val outH=height?:h
            require(outW>0&&outH>0&&kotlin.math.abs(outW.toDouble()/outH-w.toDouble()/h)<0.005)
            profile.copy(width=outW,height=outH,fx=profile.fx*outW/w,fy=profile.fy*outH/h,cx=profile.cx*outW/w,cy=profile.cy*outH/h)
        }.getOrNull()
    }
}
