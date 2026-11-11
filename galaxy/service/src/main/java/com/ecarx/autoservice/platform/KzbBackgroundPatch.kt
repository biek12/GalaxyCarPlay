package com.ecarx.autoservice.platform

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/** 沿已实车通过的Dark_BG1固定PNG区间替换，其余KZB字节原样保留。 */
internal object KzbBackgroundPatch {
    const val PNG_OFFSET=11272
    const val PNG_BYTES=399819
    private const val ORIGINAL_SHA="f8e89db53d1e2d25f23b2042baf1fcc5944a0f9a2fec8a921ed78677e5c1202a"
    fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun originalMatches(bytes:ByteArray)=sha(bytes)==ORIGINAL_SHA
    fun replace(original:ByteArray,png:ByteArray):ByteArray {
        check(sha(original)==ORIGINAL_SHA){"cluster_background_material_mismatch"}
        require(png.size>=45 && png.size<=PNG_BYTES && png.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))){"cluster_background_png_invalid"}
        val header=ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN)
        require(header.getInt(16)==1920 && header.getInt(20)==532 && png[24]==8.toByte() && png[25]==2.toByte()) {"cluster_background_png_format"}
        var offset=8;var ended=false
        while(offset+12<=png.size) {
            val size=header.getInt(offset);require(size>=0 && offset.toLong()+12+size<=png.size){"cluster_background_png_invalid"}
            val crc=CRC32().apply{update(png,offset+4,size+4)}.value.toInt()
            require(crc==header.getInt(offset+8+size)){"cluster_background_png_crc"}
            val iend=header.getInt(offset+4)==0x49454e44
            offset+=size+12
            if(iend){ended=size==0 && offset==png.size;break}
        }
        require(ended){"cluster_background_png_invalid"}
        val replacement=if(png.size==PNG_BYTES)png else {
            val label="Comment\u0000G733 generated background ".toByteArray(Charsets.UTF_8)
            val padding=PNG_BYTES-png.size-12-label.size
            require(padding>=0){"cluster_background_png_too_large"}
            val chunk=ByteArrayOutputStream();val out=DataOutputStream(chunk)
            val body="tEXt".toByteArray()+label+ByteArray(padding){32}
            out.writeInt(body.size-4);out.write(body);out.writeInt(CRC32().apply{update(body)}.value.toInt())
            png.copyOfRange(0,png.size-12)+chunk.toByteArray()+png.copyOfRange(png.size-12,png.size)
        }
        return original.copyOf().also{replacement.copyInto(it,PNG_OFFSET)}
    }
    fun proof(candidate:ByteArray):ByteArray {
        var fnv=0xcbf29ce484222325UL.toLong()
        candidate.forEach{fnv=(fnv xor(it.toLong()and 255))*1099511628211L}
        return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putInt(0x31504b47).putInt(1)
            .putLong(candidate.size.toLong()).putLong(fnv).putLong(0).array()
    }
}
