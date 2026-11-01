package com.galaxy.autocluster.theme

import android.graphics.Bitmap
import kotlin.math.*

/** 仅在IO换图/修改模糊参数时调用：先限制到256px，再三次分离盒滤波近似柔焦。
 * CPU成本与原始封面像素数、帧率无关；结果由当前画面引用，绝不在绘制中重算。
 */
internal object ArtworkBlur {
    fun create(source: Bitmap,radius: Int): Bitmap {
        val scale=min(1f,256f/max(source.width,source.height))
        val width=max(1,(source.width*scale).roundToInt());val height=max(1,(source.height*scale).roundToInt())
        val small=Bitmap.createScaledBitmap(source,width,height,true)
        val input=IntArray(width*height);small.getPixels(input,0,width,0,0,width,height)
        val output=IntArray(input.size);val r=(radius*scale).roundToInt().coerceIn(1,32)
        fun pass(src: IntArray,dst: IntArray,horizontal: Boolean){
            val length=if(horizontal)width else height;val lines=if(horizontal)height else width
            fun index(line: Int,at: Int)=if(horizontal)line*width+at else at*width+line
            for(line in 0 until lines){
                var a=0;var red=0;var green=0;var blue=0
                fun add(at: Int,sign: Int){val color=src[index(line,at.coerceIn(0,length-1))];a+=(color ushr 24)*sign;red+=(color ushr 16 and 255)*sign;green+=(color ushr 8 and 255)*sign;blue+=(color and 255)*sign}
                for(at in -r..r)add(at,1)
                val n=2*r+1
                for(at in 0 until length){dst[index(line,at)]=(a/n shl 24)or(red/n shl 16)or(green/n shl 8)or(blue/n);add(at-r,-1);add(at+r+1,1)}
            }
        }
        repeat(3){pass(input,output,true);pass(output,input,false)}
        return Bitmap.createBitmap(input,width,height,Bitmap.Config.ARGB_8888)
    }
}
