package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MediaDuckingGainTest {
    private fun pcm(frames: Int, channels: Int = 1) = ByteArray(frames * channels * 2).apply {
        for (i in indices step 2) { this[i] = 0x10; this[i + 1] = 0x27 } // 10000
    }
    private fun sample(data: ByteArray, frame: Int, channels: Int = 1, channel: Int = 0): Int {
        val i = (frame * channels + channel) * 2
        return (data[i].toInt() and 255) or (data[i + 1].toInt() shl 8)
    }
    @Test fun rampsDownAndBackOverRequestedNumberOfFrames() {
        val gain = MediaDuckingGain(1000, 1)
        gain.target(-20f, 100)
        val down = pcm(100).also { gain.apply(it, 0, it.size) }
        assertTrue(sample(down, 0) > sample(down, 98))
        assertEquals(1000, sample(down, 99))
        gain.target(0f, 100)
        val up = pcm(100).also { gain.apply(it, 0, it.size) }
        assertTrue(sample(up, 0) < sample(up, 98))
        assertEquals(10000, sample(up, 99))
    }
    @Test fun splitBlocksAndStereoUseTheSameFrameClock() {
        val whole = MediaDuckingGain(48000, 2).apply { target(-18f, 500) }
        val split = MediaDuckingGain(48000, 2).apply { target(-18f, 500) }
        val a = pcm(24000, 2).also { whole.apply(it, 0, it.size) }
        val b = pcm(24000, 2)
        for (offset in b.indices step 960) split.apply(b, offset, minOf(960, b.size - offset))
        assertArrayEquals(a, b)
        for (frame in 0 until 24000) assertEquals(sample(a, frame, 2, 0), sample(a, frame, 2, 1))
    }
    @Test fun zeroGainAndInvalidTargetNeverAmplifyAndRespectTheSelectedRange() {
        val gain = MediaDuckingGain(1000, 1)
        val original = pcm(6)
        val data = original.copyOf()
        gain.target(Float.NaN, 0); gain.apply(data, 2, 8)
        assertArrayEquals(original, data)
        gain.target(-20f, 0); gain.apply(data, 2, 8)
        assertEquals(10000, sample(data, 0)); assertEquals(10000, sample(data, 5))
        assertEquals(1000, sample(data, 1))
    }
}
