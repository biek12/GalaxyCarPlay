package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class GalaxyKanziMusicFrameTest {
    @Test fun fourLinesFollowPhoneTimelineAndSeek() {
        val lyric = GalaxyOemLyrics("[00:01.10]一\n[00:02.200]二\n[00:03]三\n[00:04]四", "二")
        assertEquals(listOf("一", "二", "三", "四"), GalaxyKanziMusicFrame.lines(lyric, 2200))
        assertEquals(listOf("", "一", "二", "三"), GalaxyKanziMusicFrame.lines(lyric, 1100))
        assertEquals(listOf("", "", "一", "二"), GalaxyKanziMusicFrame.lines(lyric, 0))
        assertEquals(listOf("", "原始当前句", "", ""), GalaxyKanziMusicFrame.lines(GalaxyOemLyrics(sentence="原始当前句"), 0))
    }
    @Test fun boundedUtf8HeaderAndFocusOwnership() {
        val bytes = GalaxyKanziMusicFrame.encode(CarPlayNowPlaying(title="🎵".repeat(400), artist="歌手",
            playing=true, playbackKnown=true, elapsedMillis=1234, durationMillis=10000),
            GalaxyOemLyrics(sentence="当前句"),true,UUID(42,43),1,1000)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("GKM1", String(bytes.copyOfRange(0,4),Charsets.US_ASCII))
        assertEquals(bytes.size,b.getInt(4)); assertEquals(15,b.getShort(10).toInt())
        assertEquals(42,b.getLong(12)); assertEquals(1,b.getLong(28)); assertEquals(8000,b.getLong(36))
        assertEquals(1234,b.getLong(44)); assertEquals(1024,b.getShort(60).toInt())
        val title=String(bytes.copyOfRange(72,1096),Charsets.UTF_8)
        assertEquals("🎵".repeat(256),title); assertFalse(title.contains('\uFFFD'))
        val ended=GalaxyKanziMusicFrame.encode(CarPlayNowPlaying(title="曲目",playbackKnown=true),GalaxyOemLyrics(),
            false,UUID(42,43),2,1000)
        assertEquals(72,ended.size)
        assertEquals(0,ByteBuffer.wrap(ended).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt())
    }
    @Test fun noSyntheticPhoneTitleAndNoNetworkLyricsFallback() {
        val bytes=GalaxyKanziMusicFrame.encode(CarPlayNowPlaying(playbackKnown=true),GalaxyOemLyrics(),true,UUID(1,2),1,1000)
        assertEquals(72,bytes.size)
        assertEquals(0,ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt())
    }
}
