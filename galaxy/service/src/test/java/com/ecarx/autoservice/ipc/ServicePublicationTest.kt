package com.ecarx.autoservice.ipc

import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.ecarx.autoservice.media.ServiceMediaSession
import com.ecarx.autoservice.oem.ClusterElementsSession
import com.ecarx.autoservice.remote.ServiceRemoteSession
import com.ecarx.autoservice.vehicle.ServiceVehicleSession
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.contract.IAutoCallback
import com.galaxy.auto.contract.MapStreamSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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

    @Test
    fun `真实发布按owner顺序输出，元素和曲目决定条件字段`() {
        val app = RuntimeEnvironment.getApplication()
        val main = Handler(Looper.getMainLooper())
        val registry = ClientRegistry {}
        val stages = mutableListOf<String>()
        val collectorCalls = mutableListOf<String>()
        var paused = false
        var root = true
        val media =
            ServiceMediaSession(
                context = app,
                main = main,
                postArtwork = {},
                postCallback = { it.run() },
                clients = registry,
                closed = { false },
                paused = { paused },
                featuresRoot = { root },
                automatic = { false },
                onPublish = {},
                onKanziPublished = { _, _ -> },
                sourceFactory = {
                    object : ServiceMediaSession.SourcePort {
                        override fun enabled(value: Boolean) {
                            stages += "source"
                        }

                        override fun close() = Unit
                    }
                },
            )
        val elements =
            ClusterElementsSession(
                context = app,
                main = main,
                postBackground = {},
                postCallback = { it.run() },
                clients = registry,
                closed = { false },
                onPublish = {},
                hookFactory = {
                    object : ClusterElementsSession.HookPort {
                        override fun update(
                            active: Boolean,
                            ready: Boolean,
                            config: ClusterDisplayConfiguration?,
                        ) {
                            stages += "hook-update"
                        }

                        override fun published(
                            generation: Long,
                            flags: Int,
                        ) = Unit

                        override fun snapshot() = Bundle().apply { putString("state", "idle") }

                        override fun close() = Unit
                    }
                },
            )
        val remote =
            ServiceRemoteSession(
                context = app,
                main = main,
                postCallback = { it.run() },
                closed = { false },
                paused = { paused },
                onPublish = {},
                clients = registry,
                webFactory = {
                    object : ServiceRemoteSession.WebPort {
                        override fun start() {
                            stages += "web"
                        }

                        override fun close() {
                            stages += "web-close"
                        }

                        override fun request(
                            action: String,
                            payload: Bundle,
                            reply: (Bundle) -> Unit,
                        ) = Unit

                        override fun upload(
                            clipId: String,
                            direction: String,
                            fd: ParcelFileDescriptor,
                            length: Long,
                            reply: (Bundle) -> Unit,
                        ) = Unit
                    }
                },
                commandFactory = {
                    object : ServiceRemoteSession.CommandPort {
                        override fun start() = Unit

                        override fun close() = Unit

                        override fun revoke() = Unit
                    }
                },
            )
        val vehicle =
            ServiceVehicleSession(
                context = app,
                clients = registry,
                main = main,
                post = { it.run() },
                isFeedEnabled = { false },
                acceptValues = {},
                sentinelDemands = { emptyMap() },
                onPublish = {},
                rootLost = {},
                collectorFactory = {
                    object : ServiceVehicleSession.CollectorPort {
                        override fun start() {
                            collectorCalls += "start"
                        }

                        override fun stop() {
                            collectorCalls += "stop"
                        }

                        override fun close() {
                            collectorCalls += "close"
                        }

                        override fun setDemand(next: Map<String, Int>) {
                            collectorCalls += "demand"
                        }

                        override fun setScan(enabled: Boolean) {
                            collectorCalls += "scan"
                        }

                        override fun scanning() = false

                        override fun stats() = "recorded"
                    }
                },
            )
        val snapshots = ServiceSnapshots(post = { it.run() }, remove = {}, isClosed = { false })
        val publication =
            ServicePublication(
                media = media,
                elements = elements,
                remote = remote,
                vehicle = vehicle,
                clients = registry,
                snapshots = snapshots,
                composer = ServiceSnapshotComposer(clock = { 42L }),
                sentinel = { null },
                closed = { false },
                paused = { paused },
                rootReady = { false },
                featuresRoot = { root },
                featuresVehicle = { false },
                automatic = { false },
                vehicleWebEnabled = { false },
                features = { Bundle() },
                foundation = { "{}" },
                status = { "created" },
                vehicleMode = { "app" },
                onNative = { stages += "native" },
                onSentinel = { stages += "sentinel" },
                onRemote = { stages += "remote" },
                onBuild = { stages += "build" },
                onHook = { stages += "hook" },
                onKanzi = { stages += "kanzi" },
                onSnapshots = { stages += "snapshots" },
            )
        try {
            media.publishCarPlay(track("song", "原词"))
            assertTrue(media.publishVisual(track("song", "可视")))
            publication.publish()
            assertEquals(
                listOf("native", "source", "sentinel", "remote", "web", "build", "hook", "kanzi", "snapshots"),
                stages.toList(),
            )
            val plain = snapshots.current()
            assertEquals(requiredKeys + setOf("visualMedia"), plain.keySet())
            assertFalse(plain.containsKey("clusterElements"))
            assertFalse(plain.containsKey("vehicle"))
            assertEquals("可视", plain.getBundle("visualMedia")?.getStringArrayList("lyrics")?.single())
            assertFalse(plain.getBoolean("clusterElementsRequested"))
            assertFalse(plain.getBoolean("qnxReady"))
            assertFalse(plain.getBoolean("projectionRequested"))
            assertEquals(MapStreamSize.DEFAULT.width, plain.getBundle("mapStreamSize")?.getInt("width"))
            assertTrue(plain.getStringArray("vehicleKeys")!!.isNotEmpty())
            assertEquals("not_connected", plain.getString("vehicleDiscovery"))
            assertTrue(collectorCalls.isEmpty())

            val owner = Binder()
            val lease = registry.attach(owner, 4, AutoContract.ELEMENTS, callback())
            lease.active = true
            lease.elements =
                ClusterDisplayConfiguration(
                    ClusterDisplayConfiguration.OEM_ELEMENTS,
                    ClusterDisplayConfiguration.IMAGE_BACKGROUND,
                    true,
                    "panel.png",
                )
            stages.clear()
            publication.publish()
            assertEquals(
                listOf(
                    "native",
                    "source",
                    "sentinel",
                    "remote",
                    "build",
                    "hook",
                    "hook-update",
                    "kanzi",
                    "snapshots",
                ),
                stages.toList(),
            )
            val withElements = snapshots.current()
            assertEquals(requiredKeys + setOf("clusterElements", "visualMedia"), withElements.keySet())
            assertTrue(withElements.getBoolean("clusterElementsRequested"))
            assertEquals("panel.png", withElements.getBundle("clusterElements")?.getString("image"))
            assertEquals("idle", withElements.getBundle("clusterElementRuntime")?.getString("state"))
            assertEquals(2L, withElements.getLong("sequence"))

            paused = true
            media.publishCarPlay(track("other", "下一首"))
            stages.clear()
            publication.publish()
            assertTrue(stages.contains("web-close"))
            assertTrue(stages.indexOf("native") < stages.indexOf("sentinel"))
            assertTrue(stages.indexOf("sentinel") < stages.indexOf("remote"))
            assertTrue(stages.indexOf("remote") < stages.indexOf("build"))
            assertTrue(stages.indexOf("build") < stages.indexOf("hook"))
            assertTrue(stages.indexOf("hook") < stages.indexOf("kanzi"))
            assertTrue(stages.indexOf("kanzi") < stages.indexOf("snapshots"))
            val pausedSnapshot = snapshots.current()
            assertFalse(pausedSnapshot.getBoolean("clusterElementsRequested"))
            assertFalse(pausedSnapshot.containsKey("clusterElements"))
            assertNull(pausedSnapshot.getBundle("visualMedia"))

            paused = false
            root = false
            stages.clear()
            publication.publish()
            assertFalse(snapshots.current().getBoolean("clusterElementsRequested"))
            assertFalse(snapshots.current().containsKey("clusterElements"))
            assertTrue(collectorCalls.isEmpty())
            shadowOf(main.looper).idle()
        } finally {
            registry.close()
            remote.closeWeb()
            remote.closeRemote()
            elements.closeHook()
            media.closeNativeSource()
        }
    }

    private val requiredKeys =
        setOf(
            "artworkReady",
            "artworkUri",
            "artworkVersion",
            "automatic",
            "clusterConfigurationStatus",
            "clusterElementRevision",
            "clusterElementRuntime",
            "clusterElementStatus",
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
        )

    private fun track(
        name: String,
        lyric: String,
    ): Bundle =
        Bundle().apply {
            putString("track", name)
            putStringArrayList("lyrics", arrayListOf(lyric))
        }

    private fun callback(): IAutoCallback =
        object : IAutoCallback.Stub() {
            override fun onSnapshot(snapshot: Bundle) = Unit

            override fun onSurface(
                role: String,
                surface: Surface,
            ) = Unit

            override fun onProjectionState(
                role: String,
                state: String,
            ) = Unit

            override fun onVehicleFeed(values: Bundle) = Unit
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
