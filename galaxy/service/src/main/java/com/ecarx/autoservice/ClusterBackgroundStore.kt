package com.ecarx.autoservice

import android.content.Context
import android.graphics.*
import android.os.ParcelFileDescriptor
import java.io.File

/** 图像FD在普通服务身份下解码；Root只取得本App生成的固定格式资源。 */
internal class ClusterBackgroundStore(context: Context) {
    private val directory=File(context.noBackupFilesDir,"cluster-backgrounds").apply{mkdirs()}
    fun file(name:String):File {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,95}\\.(png|jpg|jpeg|webp)")))
        return File(directory,"$name.rgba")
    }
    fun import(name:String,fd:ParcelFileDescriptor) {
        val target=file(name)
        val bytes=ParcelFileDescriptor.AutoCloseInputStream(fd).use { input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=16*1024*1024){"background_size"};out.write(buffer,0,n)}
            out.toByteArray()
        }
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384){"background_dimensions"}
        val options=BitmapFactory.Options().apply{inSampleSize=1}
        while(bounds.outWidth/options.inSampleSize>3840 || bounds.outHeight/options.inSampleSize>2160)options.inSampleSize*=2
        val source=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?:error("background_decode")
        val output=Bitmap.createBitmap(1920,532,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(output);canvas.drawColor(Color.BLACK)
            val scale=minOf(1920f/source.width,532f/source.height)
            val w=source.width*scale;val h=source.height*scale
            canvas.drawBitmap(source,null,RectF((1920-w)/2,(532-h)/2,(1920+w)/2,(532+h)/2),Paint(Paint.FILTER_BITMAP_FLAG))
            val png=ClusterBackgroundPng.encode(bytes,output)
            require(png.size<=com.ecarx.autoservice.platform.KzbBackgroundPatch.PNG_BYTES-64){"background_png_too_large"}
            val image=android.util.AtomicFile(File(directory,"$name.rgb.png"));val imageOutput=image.startWrite()
            try{imageOutput.write(png);image.finishWrite(imageOutput)}catch(e:Exception){image.failWrite(imageOutput);throw e}
            val row=IntArray(1920);val rgba=ByteArray(7680)
            val atomic=android.util.AtomicFile(target);val stream=atomic.startWrite()
            try {
                for(y in 0 until 532){output.getPixels(row,0,1920,0,y,1920,1);row.forEachIndexed{i,c->rgba[i*4]=(c ushr 16).toByte();rgba[i*4+1]=(c ushr 8).toByte();rgba[i*4+2]=c.toByte();rgba[i*4+3]=255.toByte()};stream.write(rgba)}
                atomic.finishWrite(stream)
            } catch(e:Exception){atomic.failWrite(stream);throw e}
        } finally {source.recycle();output.recycle()}
    }
}
