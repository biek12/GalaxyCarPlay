package com.ecarx.autoservice.ipc

import android.os.Bundle
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.MapStreamSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 调用生产 [ServiceSnapshotComposer]。
 * 字段在 build 时读取，sequence 和时钟由这次调用推进。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ServicePublicationTest {
    @Test
    fun `快照包含合同字段，归档位为假，可视歌词只在曲目一致时合并`() {
        val composer = ServiceSnapshotComposer(clock = { 42L })
        val first = composer.build(input(track = "song", visualTrack = "song", lyrics = arrayListOf("一行")))
        assertEquals(
            setOf(
                "artworkReady",
                "artworkUri",
                "artworkVersion",
                "automatic",
                "clusterElementRevision",
                "clusterElementsRequested",
                "clusterLyricsRequested",
                "clusterMusicRequested",
                "features",
                "foundation",
                "inputEpoch",
                "mapStreamSize",
                "media",
                "menuReceivedAt",
                "menuSequence",
                "minVehicleFeedIntervalMs",
                "model",
                "navigation",
                "projectionPolicy",
                "projectionRequested",
                "qnxReady",
                "receivedAt",
                "remoteState",
                "rootReady",
                "sentinel",
                "sentinelVersion",
                "sequence",
                "status",
                "vehicleDiscovery",
                "vehicleKeys",
                "vehicleMode",
                "vehiclePropertyCount",
                "vehicleSampledCount",
                "vehicleScanning",
                "vehicleValidCount",
                "vehicleWebEnabled",
                "version",
                "clusterElements",
                "visualMedia",
            ),
            first.keySet(),
        )
        assertFalse(first.getBoolean("qnxReady"))
        assertFalse(first.getBoolean("projectionRequested"))
        assertFalse(first.containsKey("vehicle"))
        assertEquals(1L, first.getLong("sequence"))
        assertEquals(42L, first.getLong("receivedAt"))
        assertEquals(AutoContract.VERSION, first.getInt("version"))
        assertEquals(1, first.getInt("sentinelVersion"))
        assertEquals("content://com.ecarx.autoservice.artwork/current", first.getString("artworkUri"))
        assertEquals(MapStreamSize.DEFAULT.width, first.getBundle("mapStreamSize")?.getInt("width"))
        assertEquals("一行", first.getBundle("visualMedia")?.getStringArrayList("lyrics")?.single())
        val second = composer.build(input(track = "song", visualTrack = "other", lyrics = arrayListOf("不合并")))
        assertEquals(2L, second.getLong("sequence"))
        assertNull(second.getBundle("visualMedia"))
        assertEquals("原词", second.getBundle("media")?.getStringArrayList("lyrics")?.single())
    }

    private fun input(
        track: String,
        visualTrack: String,
        lyrics: ArrayList<String>,
    ): ServiceSnapshotComposer.Input {
        val media =
            Bundle().apply {
                putString("track", track)
                putStringArrayList("lyrics", arrayListOf("原词"))
                putLongArray("lyricTimes", longArrayOf(1))
            }
        val visual =
            Bundle().apply {
                putString("track", visualTrack)
                putStringArrayList("lyrics", lyrics)
            }
        return ServiceSnapshotComposer.Input(
            vehicleDiscovery = { "idle" },
            vehiclePropertyCount = { 1 },
            vehicleSampledCount = { 2 },
            vehicleValidCount = { 3 },
            sentinel = { Bundle().apply { putInt("sentinelVersion", 9) } },
            rootReady = { true },
            automatic = { false },
            vehicleWebEnabled = { false },
            features = { Bundle() },
            clusterElementsRequested = { true },
            clusterElementRevision = { 4L },
            clusterMusicRequested = { true },
            clusterLyricsRequested = { true },
            clusterElements = { Bundle().apply { putString("background", "image") } },
            foundation = { "{}" },
            status = { "created" },
            media = { media },
            navigation = { Bundle() },
            visualMedia = { visual },
            vehicleKeys = { arrayOf("displaySpeed") },
            vehicleScanning = { false },
            inputEpoch = { "epoch" },
            menuSequence = { 0L },
            menuReceivedAt = { 0L },
            remoteState = { "disabled" },
            vehicleMode = { "app" },
            model = { "L6" },
            artworkReady = { false },
            artworkVersion = { 0L },
        )
    }
}
