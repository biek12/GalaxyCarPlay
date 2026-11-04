package com.galaxy.autorecord.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class SegmentManifestTest {
    private fun manifest(state: String = SegmentManifest.STATE_COMPLETE, statuses: List<String>) =
        SegmentManifest(
            segmentId = "20261103-221500-000",
            startedAtWallMillis = 1_700_000_000_000L,
            startedAtMonotonicNanos = 123_456_789L,
            durationMillis = 60_000L,
            state = state,
            storageVolumeUuid = "1A2B-3C4D",
            channels = SegmentLayout.DIRECTIONS.mapIndexed { i, dir ->
                ChannelRecord(
                    direction = dir,
                    file = "$dir.mp4",
                    codecMime = RecordSettings.CODEC_H264,
                    encoderName = "c2.galaxy.avc.encoder",
                    implementation = if (i == 3) "software" else "hardware",
                    width = 1280,
                    height = 720,
                    fps = 25,
                    bitrateBps = 2_000_000L,
                    gopSeconds = 1,
                    frameCount = 1500L,
                    firstPtsUs = 0L,
                    lastPtsUs = 59_960_000L,
                    status = statuses[i],
                    missingRanges = if (statuses[i] == SegmentManifest.CHANNEL_PARTIAL) listOf(10_000_000L, 12_000_000L) else emptyList(),
                )
            },
            vehicle = VehicleRecord(
                intervalMs = 500,
                keys = listOf("speed", "gear", "brakePercent"),
                sampleCount = 120L,
                file = SegmentLayout.FILE_VEHICLES,
                clockDomain = "elapsedRealtimeNanos",
            ),
            events = EventsRecord(ruleVersion = "brake:5/3 accel:20/10", count = 3, file = SegmentLayout.FILE_EVENTS),
            appVersionName = "0.1.0",
        )

    @Test
    fun `完整片段序列化后可原样读回`() {
        val m = manifest(statuses = List(4) { SegmentManifest.CHANNEL_COMPLETE })
        val back = SegmentManifest.fromJson(m.toJson().toString())
        assertEquals(m, back)
        assertTrue(back!!.isComplete)
    }

    @Test
    fun `缺一路即不完整且缺路方向可定位`() {
        val m = manifest(statuses = listOf(
            SegmentManifest.CHANNEL_COMPLETE,
            SegmentManifest.CHANNEL_MISSING,
            SegmentManifest.CHANNEL_COMPLETE,
            SegmentManifest.CHANNEL_COMPLETE,
        ))
        val back = SegmentManifest.fromJson(m.toJson().toString())!!
        assertFalse(back.isComplete)
        assertEquals("rear", back.channels.first { it.status == SegmentManifest.CHANNEL_MISSING }.direction)
    }

    @Test
    fun `部分有效片段保留缺路区间与真实编码实现`() {
        val m = manifest(
            state = SegmentManifest.STATE_PARTIAL,
            statuses = listOf(
                SegmentManifest.CHANNEL_COMPLETE,
                SegmentManifest.CHANNEL_COMPLETE,
                SegmentManifest.CHANNEL_PARTIAL,
                SegmentManifest.CHANNEL_COMPLETE,
            ),
        )
        val back = SegmentManifest.fromJson(m.toJson().toString())!!
        assertFalse(back.isComplete)
        assertEquals(listOf(10_000_000L, 12_000_000L), back.channels[2].missingRanges)
        assertEquals("software", back.channels[3].implementation)
        assertEquals("1A2B-3C4D", back.storageVolumeUuid)
    }

    @Test
    fun `内置存储与缺失 sidecar 都不炸`() {
        val m = manifest(statuses = List(4) { SegmentManifest.CHANNEL_COMPLETE })
            .copy(storageVolumeUuid = null, vehicle = null, events = null)
        val back = SegmentManifest.fromJson(m.toJson().toString())!!
        assertNull(back.storageVolumeUuid)
        assertNull(back.vehicle)
        assertNull(back.events)
        assertNull(SegmentManifest.fromJson("not json"))
        assertNull(SegmentManifest.fromJson(JSONObject().put("x", 1).toString()))
    }
}