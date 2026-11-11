package com.ecarx.autoservice

import android.os.Bundle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** 与原生GKM1相同的有界合同；只写自有资源，不触及原厂媒体原始通道。 */
internal object KanziMediaFrame {
    fun encode(media: Bundle,active: Boolean,session: UUID,revision: Long,now: Long,elapsedNow: Long): ByteArray {
        val enabled=active&&!media.getString("title").isNullOrBlank()
        val duration=media.getLong("duration",-1)
        val sampled=media.getLong("receivedAt",elapsedNow)
        val position=(media.getLong("elapsed",0).coerceAtLeast(0)+if(media.getBoolean("playing"))(elapsedNow-sampled).coerceAtLeast(0)else 0).let{if(duration>0)it.coerceAtMost(duration)else it}
        val lyrics=media.getStringArrayList("lyrics").orEmpty();val times=media.getLongArray("lyricTimes")
        val index=if(times!=null&&times.size==lyrics.size)times.indexOfLast{it<=position}else 0
        val fields=if(enabled)listOf(media.getString("title").orEmpty(),media.getString("artist").orEmpty())+(-1..2).map{lyrics.getOrNull(index+it).orEmpty()}else List(6){""}
        val bytes=fields.map(::utf8)
        val flags=(if(enabled)1 else 0)or(if(enabled&&media.getBoolean("playing"))2 else 0)or(if(enabled&&media.getLong("elapsed",-1)>=0)4 else 0)or(if(enabled&&duration>=0)8 else 0)
        return ByteBuffer.allocate(72+bytes.sumOf{it.size}).order(ByteOrder.LITTLE_ENDIAN).apply{
            put(byteArrayOf(71,75,77,49));putInt(capacity());putShort(1);putShort(flags.toShort())
            putLong(session.mostSignificantBits);putLong(session.leastSignificantBits);putLong(revision);putLong(now+7000)
            putLong(position.coerceIn(0,604800000));putLong(duration.coerceIn(0,604800000))
            bytes.forEach{putShort(it.size.toShort())};bytes.forEach(::put)
        }.array()
    }
    fun coverKey(media: Bundle,session: UUID): Long {
        var value=0xcbf29ce484222325UL.toLong()
        val sessionBytes=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(session.mostSignificantBits).putLong(session.leastSignificantBits).array()
        (sessionBytes+utf8(media.getString("title").orEmpty())+byteArrayOf(0)+utf8(media.getString("artist").orEmpty())+byteArrayOf(0)).forEach{value=(value xor(it.toLong()and 255))*1099511628211L}
        return value
    }
    private fun utf8(value: String): ByteArray {
        val clean=value.replace('\u0000',' ').replace('\r',' ').replace('\n',' ');val out=java.io.ByteArrayOutputStream()
        var index=0
        while(index<clean.length){val cp=clean.codePointAt(index);val bytes=String(Character.toChars(cp)).toByteArray();if(out.size()+bytes.size>1024)break;out.write(bytes);index+=Character.charCount(cp)}
        return out.toByteArray()
    }
}
