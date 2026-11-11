package com.ecarx.autoservice

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Kanzi原图是RGB8 PNG；保留已符合尺寸的原PNG，否则从完整适配画面无损编码。 */
internal object ClusterBackgroundPng {
    fun encode(original:ByteArray,bitmap:Bitmap):ByteArray {
        if(original.size in 45..(com.ecarx.autoservice.platform.KzbBackgroundPatch.PNG_BYTES-64)) {
            val h=ByteBuffer.wrap(original).order(ByteOrder.BIG_ENDIAN)
            if(h.getLong(0)==0x89504e470d0a1a0aUL.toLong() && h.getInt(16)==1920 && h.getInt(20)==532 && original[24]==8.toByte() && original[25]==2.toByte())return original
        }
        val compressed=ByteArrayOutputStream();val deflater=Deflater(9)
        try { DeflaterOutputStream(compressed,deflater).use { zip ->
            val colors=IntArray(1920);var previous=ByteArray(1920*3)
            for(y in 0 until 532) {
                bitmap.getPixels(colors,0,1920,0,y,1920,1)
                val row=ByteArray(1920*3)
                colors.forEachIndexed{i,c->row[i*3]=(c ushr 16).toByte();row[i*3+1]=(c ushr 8).toByte();row[i*3+2]=c.toByte()}
                var best=row;var filter=0;var score=row.sumOf{kotlin.math.abs(it.toInt())}
                for(kind in intArrayOf(1,2,4)) {
                    val candidate=ByteArray(row.size);var weight=0
                    for(i in row.indices) {
                        val left=if(i>=3)row[i-3].toInt()and 255 else 0
                        val up=previous[i].toInt()and 255;val corner=if(i>=3)previous[i-3].toInt()and 255 else 0
                        val p=left+up-corner;val a=kotlin.math.abs(p-left);val b=kotlin.math.abs(p-up);val c=kotlin.math.abs(p-corner)
                        val predictor=when(kind){1->left;2->up;else->if(a<=b&&a<=c)left else if(b<=c)up else corner}
                        candidate[i]=((row[i].toInt()and 255)-predictor).toByte();weight+=kotlin.math.abs(candidate[i].toInt())
                    }
                    if(weight<score){best=candidate;filter=kind;score=weight}
                }
                zip.write(filter);zip.write(best);previous=row
            }
        }} finally {deflater.end()}
        val bytes=ByteArrayOutputStream();val output=DataOutputStream(bytes)
        output.write(byteArrayOf(-119,80,78,71,13,10,26,10))
        fun chunk(name:String,data:ByteArray) {val type=name.toByteArray();output.writeInt(data.size);output.write(type);output.write(data);output.writeInt(CRC32().apply{update(type);update(data)}.value.toInt())}
        chunk("IHDR",ByteBuffer.allocate(13).putInt(1920).putInt(532).put(byteArrayOf(8,2,0,0,0)).array())
        chunk("IDAT",compressed.toByteArray());chunk("IEND",ByteArray(0));return bytes.toByteArray()
    }
}
