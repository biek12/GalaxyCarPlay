package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** 自有Kanzi音乐组件合同v1；仅消费原厂媒体端口收到的iPhone原始数据。 */
internal object GalaxyKanziMusicFrame {
    const val MAX_BYTES = 8192
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private data class Line(val time: Long, val text: String)

    fun lines(lyrics: GalaxyOemLyrics, position: Long): List<String> {
        val rows = lyrics.content.lineSequence().take(1000).flatMap { raw ->
            val matches = timestamp.findAll(raw).take(16).filter { it.groupValues[2].toInt() < 60 }.toList()
            val text = raw.replace(timestamp, "").trim()
            matches.asSequence().map { match ->
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                Line(match.groupValues[1].toLong() * 60000 + match.groupValues[2].toLong() * 1000 + fraction, text)
            }
        }.take(1000).sortedBy { it.time }.toList()
        if (rows.isEmpty()) return listOf("", lyrics.sentence, "", "")
        val index = rows.indexOfLast { it.time <= position }
        return (-1..2).map { delta -> rows.getOrNull(index + delta)?.text.orEmpty() }
    }

    fun encode(value: CarPlayNowPlaying, lyrics: GalaxyOemLyrics, ownFocus: Boolean,
               session: UUID, revision: Long, nowMillis: Long): ByteArray {
        require(revision > 0)
        val active = ownFocus && value.playbackKnown && !value.title.isNullOrBlank()
        val position = if (active) (value.elapsedMillis ?: 0).coerceIn(0, 604800000) else 0L
        val fields = (if (active) listOf(value.title.orEmpty(), value.artist.orEmpty()) + lines(lyrics, position)
            else List(6) { "" }).map(::utf8)
        val size = 72 + fields.sumOf { it.size }
        require(size <= MAX_BYTES)
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(71, 75, 77, 49)); putInt(size); putShort(1)
            val flags = (if (active) 1 else 0) or (if (active && value.playing) 2 else 0) or
                (if (active && value.elapsedMillis != null) 4 else 0) or
                (if (active && value.durationMillis != null) 8 else 0)
            putShort(flags.toShort())
            putLong(session.mostSignificantBits); putLong(session.leastSignificantBits)
            putLong(revision); putLong(nowMillis + 7000); putLong(position)
            putLong(if (active) (value.durationMillis ?: 0).coerceIn(0, 604800000) else 0L)
            fields.forEach { putShort(it.size.toShort()) }; fields.forEach(::put)
        }.array()
    }

    /** 按码点限长，不能切断UTF-8；歌曲内容不进入Shell或日志。 */
    private fun utf8(value: String): ByteArray {
        val clean = value.replace('\u0000', ' ').replace('\r', ' ').replace('\n', ' ')
        val result = StringBuilder(); var bytes = 0; var index = 0
        while (index < clean.length) {
            val cp = clean.codePointAt(index)
            val piece = String(Character.toChars(cp)); val length = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + length > 1024) break
            result.append(piece); bytes += length; index += Character.charCount(cp)
        }
        return result.toString().toByteArray(Charsets.UTF_8)
    }
}
