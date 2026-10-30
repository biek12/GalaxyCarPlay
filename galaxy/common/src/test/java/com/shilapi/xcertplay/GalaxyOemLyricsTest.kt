package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test

class GalaxyOemLyricsTest {
    @Test fun currentLineTracksSeekPauseAndFullLrcKeepsTimestamps() {
        val state = ClusterMusicState.Snapshot(info = CarPlayNowPlaying(elapsedMillis = 1500,
            playing = true), elapsedUpdatedAt = 1000, lyrics = listOf(
            ClusterLyricLine(1000, "first"), ClusterLyricLine(2000, "second")))
        assertEquals(GalaxyOemLyrics("[00:01.00]first\n[00:02.00]second", "second"),
            GalaxyOemLyrics.from(state, 1500))
        assertEquals("first", GalaxyOemLyrics.from(state.copy(info = state.info.copy(playing = false)), 5000).sentence)
        assertEquals("", GalaxyOemLyrics.from(state.copy(info = state.info.copy(elapsedMillis = 0,
            playing = false)), 5000).sentence)
    }
}
