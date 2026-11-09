package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test

class GalaxyNowPlayingIdentityTest {
    private val song = CarPlayNowPlaying(title="Song",artist="Singer",sourceApp="Music",
        artworkTransferId=7,durationMillis=296000,elapsedMillis=1000,playing=true)

    @Test fun videoSequenceRetainsQueryIdentityThroughTitleCarrierUpdates() {
        val identity=GalaxyNowPlayingIdentity()
        assertEquals(song,identity.resolve(song))
        val carrier=song.copy(title="Song - Singer",artist="Song",elapsedMillis=2000)
        assertEquals(song.copy(elapsedMillis=2000),identity.resolve(carrier))
        assertEquals(song.copy(elapsedMillis=3000),identity.resolve(carrier.copy(title="writer credit",elapsedMillis=3000)))
        assertEquals(song.copy(elapsedMillis=4000,playing=false),identity.resolve(carrier.copy(title="verse",elapsedMillis=4000,playing=false)))
    }

    @Test fun genuineSkipAndSessionResetDoNotKeepOldIdentity() {
        val identity=GalaxyNowPlayingIdentity()
        identity.resolve(song)
        identity.resolve(song.copy(title="Song - Singer",artist="Song"))
        val next=song.copy(title="Next",artist="Other",elapsedMillis=0)
        assertEquals(next,identity.resolve(next))
        assertEquals(song,identity.resolve(song))
        identity.reset()
        val unknown=song.copy(title="credit",artist="Song")
        assertEquals(unknown,identity.resolve(unknown))
    }

    @Test fun ordinaryTitleChangesAndDifferentSourceAreNotMasked() {
        val identity=GalaxyNowPlayingIdentity()
        identity.resolve(song)
        val renamed=song.copy(title="Next")
        assertEquals(renamed,identity.resolve(renamed))
        identity.resolve(song)
        val switched=song.copy(title="Song - Singer",artist="Song",sourceApp="Other")
        assertEquals(switched,identity.resolve(switched))
    }
    @Test fun carrierDetectionDoesNotDependOnAsciiHyphenOrCombinedTitleArrivingFirst(){
        val identity=GalaxyNowPlayingIdentity()
        identity.resolve(song)
        assertEquals(song,identity.resolve(song.copy(title="词：作者",artist="Song")))
        assertEquals(song,identity.resolve(song.copy(title="Song — Singer",artist="Song")))
    }
}
