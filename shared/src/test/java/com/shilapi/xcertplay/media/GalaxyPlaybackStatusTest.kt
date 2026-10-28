package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

/** 迁自Galaxy对未知/明确暂停的区分，不从标题或音频流反推播放状态。 */
class GalaxyPlaybackStatusTest {
    private fun update(body: Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(CarPlayPlaybackStatus.NOW_PLAYING_UPDATE, body)

    @Test fun titleAndProgressDoNotMakePlaybackKnown() {
        val status = CarPlayPlaybackStatus()
        status.acceptUpdate(update { group(0) { string(1, "Song") }; group(1) { u32(1, 500) } })
        assertFalse(status.nowPlaying.playbackKnown)
        assertFalse(status.nowPlaying.playing)
    }

    @Test fun firstExplicitPauseIsPublishedExactlyOnce() {
        val status = CarPlayPlaybackStatus()
        assertEquals(false, status.accept(update { group(1) { u8(0, 2) } }))
        assertTrue(status.nowPlaying.playbackKnown)
        assertNull(status.accept(update { group(1) { u8(0, 2) } }))
        assertNull(status.accept(update { group(1) { u32(1, 1000) } }))
    }

    @Test fun malformedStatusDoesNotBecomeKnownOrClearPriorState() {
        val status = CarPlayPlaybackStatus()
        status.acceptUpdate(update { group(1) { string(0, "malformed") } })
        assertFalse(status.nowPlaying.playbackKnown)
        assertEquals(true, status.accept(update { group(1) { u8(0, 1) } }))
        status.acceptUpdate(update { group(1) { string(0, "malformed") } })
        assertTrue(status.nowPlaying.playbackKnown)
        assertTrue(status.nowPlaying.playing)
    }

    @Test fun disconnectMakesNextSessionsFirstPauseObservable() {
        val status = CarPlayPlaybackStatus()
        status.accept(update { group(1) { u8(0, 2) } })
        status.clearAll()
        assertFalse(status.nowPlaying.playbackKnown)
        assertEquals(false, status.accept(update { group(1) { u8(0, 2) } }))
    }
}
