package com.ecarx.autoservice.ipc

import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.ecarx.autoservice.media.ServiceMediaSession
import com.ecarx.autoservice.oem.ClusterElementsSession
import com.ecarx.autoservice.platform.foundation.ClientPermissionRequests
import com.ecarx.autoservice.platform.foundation.ServiceShellRequests
import com.ecarx.autoservice.remote.ServiceRemoteSession
import com.ecarx.autoservice.sentinel.ServiceSentinelRequests
import com.ecarx.autoservice.vehicle.ServiceVehicleSession
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoCallback
import com.galaxy.auto.contract.IAutoResult
import com.galaxy.auto.contract.IAutoService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * 从真实 [IAutoService.Stub.asInterface] 进入生产 [AutoServiceBinder] 和真实 owner。
 * 同步拒绝必须发生在 main 任务运行之前。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class AutoServiceBinderTest {
    private val main = Handler(Looper.getMainLooper())
    private val app get() = RuntimeEnvironment.getApplication()
    private val registry = ClientRegistry {}
    private var callingUid = 1000
    private var processUid = 1000
    private var signature = PackageManager.SIGNATURE_MATCH
    private var packages = listOf("com.ecarx.carplay")
    private var admin = true
    private var closed = false
    private val published = mutableListOf<String>()
    private val scans = mutableListOf<Boolean>()
    private val shells = mutableListOf<String>()
    private val webActions = mutableListOf<String>()
    private val sentinel = mutableListOf<String>()
    private val states = mutableListOf<String>()

    @Test
    fun `同步拒绝发生在排队前，角色归档和扫描本进程边界保持`() {
        val binder = binder()
        val api = IAutoService.Stub.asInterface(binder)
        try {
            callingUid = 2000
            signature = PackageManager.SIGNATURE_NO_MATCH
            assertThrows(IllegalStateException::class.java) { api.protocolVersion }
            shadowOf(main.looper).idle()
            assertTrue(published.isEmpty())

            callingUid = 1000
            signature = PackageManager.SIGNATURE_MATCH
            packages = listOf("com.example.other")
            val owner = Binder()
            assertThrows(IllegalStateException::class.java) { api.attach(owner, AutoContract.MAP, callback()) }
            shadowOf(main.looper).idle()
            assertTrue(registry.all().isEmpty())

            packages = listOf(AutoContract.CLUSTER_PACKAGE)
            val archived = mutableListOf<String>()
            api.attach(owner, AutoContract.THEME, callback(archived))
            shadowOf(main.looper).idle()
            assertEquals(listOf(AutoContract.THEME), archived)
            assertTrue(registry.all().isEmpty())
            assertTrue(published.isEmpty())

            packages = listOf("com.ecarx.carplay")
            api.attach(owner, AutoContract.MAP, callback())
            shadowOf(main.looper).idle()
            assertEquals(AutoContract.MAP, registry.all().single().role)
            assertEquals(listOf("publish"), published)

            admin = false
            assertThrows(IllegalStateException::class.java) {
                api.executeShell(owner, "ROOT", "id", 1000, result())
            }
            assertTrue(shells.isEmpty())

            callingUid = 1000
            processUid = 2000
            val scan = assertThrows(IllegalStateException::class.java) { api.setVehicleScan(true) }
            assertEquals("local_only", scan.message)
            assertTrue(scans.isEmpty())
            processUid = 1000
            api.setVehicleScan(true)
            assertEquals(listOf(true), scans)

            packages = listOf("com.example.other")
            assertThrows(IllegalStateException::class.java) { api.configureSentinel(owner, false, result()) }
            assertTrue(sentinel.isEmpty())

            callingUid = processUid
            packages = listOf("com.ecarx.autoservice")
            assertThrows(IllegalStateException::class.java) { api.vehicleWebRequest(owner, "nope", Bundle(), result()) }
            assertTrue(webActions.isEmpty())

            packages = listOf(AutoContract.CLUSTER_PACKAGE)
            val surface = Surface(SurfaceTexture(0))
            closed = true
            api.setPreviewSurface(owner, surface)
            shadowOf(main.looper).idle()
            assertFalse(surface.isValid)
            val image =
                ParcelFileDescriptor.open(
                    File(app.cacheDir, "image.bin").apply { writeBytes(byteArrayOf(1)) },
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    api.configureClusterImage(owner, imageConfig(), image, result())
                }
            } finally {
                image.close()
            }
        } finally {
            registry.close()
        }
    }

    private fun binder(): AutoServiceBinder {
        val identity =
            ServiceCallerIdentity(
                callingUid = { callingUid },
                processUid = { processUid },
                checkSignatures = { _, _ -> signature },
                packagesForUid = { packages.toTypedArray() },
            )
        val queue = ServiceRequestQueue(main, { false }) { _, error -> states += error.javaClass.simpleName }
        val sessions =
            ServiceClientSessions(
                clients = registry,
                main = main,
                postCallback = { it.run() },
                closed = { closed },
                recalculateDemand = {},
                clearRole = {},
                onElementsActive = { _, _ -> },
                onPublish = { published += "publish" },
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
                        override fun start() = Unit

                        override fun stop() = Unit

                        override fun close() = Unit

                        override fun setDemand(next: Map<String, Int>) = Unit

                        override fun setScan(enabled: Boolean) {
                            scans += enabled
                        }

                        override fun scanning() = scans.lastOrNull() == true

                        override fun stats() = "recorded"
                    }
                },
            )
        val media =
            ServiceMediaSession(
                context = app,
                main = main,
                postArtwork = {},
                postCallback = { it.run() },
                clients = registry,
                closed = { closed },
                paused = { false },
                featuresRoot = { false },
                automatic = { false },
                onPublish = {},
                onKanziPublished = { _, _ -> },
                sourceFactory = {
                    object : ServiceMediaSession.SourcePort {
                        override fun enabled(value: Boolean) = Unit

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
                closed = { closed },
                onPublish = {},
                hookFactory = {
                    object : ClusterElementsSession.HookPort {
                        override fun update(
                            active: Boolean,
                            ready: Boolean,
                            config: com.galaxy.auto.contract.ClusterDisplayConfiguration?,
                        ) = Unit

                        override fun published(
                            generation: Long,
                            flags: Int,
                        ) = Unit

                        override fun snapshot() = Bundle()

                        override fun close() = Unit
                    }
                },
            )
        val shell =
            ServiceShellRequests(
                postShell = { shells += "shell" },
                postMain = { it.run() },
                postCallback = { it.run() },
                executeShell = { _, _, _ -> org.json.JSONObject() },
                closed = { false },
                paused = { false },
                automatic = { true },
                leaseAlive = { true },
            )
        val permissions =
            ClientPermissionRequests(
                postShell = {},
                postMain = { it.run() },
                postCallback = { it.run() },
                executeShell = { _, _, _ -> org.json.JSONObject() },
                ensureRoot = { "ready" },
                closed = { false },
                paused = { false },
                automatic = { true },
                rootEnabled = { true },
                overlayAllowed = { _, _ -> true },
                permissionGranted = { _, _ -> true },
                leaseAlive = { true },
            )
        val remote =
            ServiceRemoteSession(
                context = app,
                main = main,
                postCallback = { it.run() },
                closed = { false },
                paused = { true },
                onPublish = {},
                leaseAlive = { true },
                webFactory = {
                    object : ServiceRemoteSession.WebPort {
                        override fun start() = Unit

                        override fun close() = Unit

                        override fun request(
                            action: String,
                            payload: Bundle,
                            reply: (Bundle) -> Unit,
                        ) {
                            webActions += action
                            reply(Bundle())
                        }

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
        return AutoServiceBinder(
            callerIdentity = identity,
            requestQueue = queue,
            packagesForUid = { packages },
            hasAdmin = { admin },
            processUid = { processUid },
            clients = registry,
            sessions = sessions,
            elements = elements,
            media = media,
            vehicle = vehicle,
            shellRequests = shell,
            permissionRequests = permissions,
            sentinelRequests =
                ServiceSentinelRequests(
                    postCallback = {
                        sentinel += "sent"
                        it.run()
                    },
                    sentinel = { null },
                    paused = { false },
                    rootEnabled = { true },
                    vehicleEnabled = { true },
                    automatic = { true },
                ),
            remote = remote,
            snapshots = ServiceSnapshots(post = { it.run() }, remove = {}, isClosed = { false }),
            publish = { published += "publish" },
        )
    }

    private fun imageConfig(): Bundle =
        com.galaxy.auto.contract
            .ClusterDisplayConfiguration(
                com.galaxy.auto.contract.ClusterDisplayConfiguration.OEM_ELEMENTS,
                com.galaxy.auto.contract.ClusterDisplayConfiguration.IMAGE_BACKGROUND,
                true,
                "image.png",
            ).bundle()

    private fun callback(states: MutableList<String> = mutableListOf()): IAutoCallback =
        object : IAutoCallback.Stub() {
            override fun onSnapshot(snapshot: Bundle) = Unit

            override fun onSurface(
                role: String,
                surface: Surface,
            ) = Unit

            override fun onProjectionState(
                role: String,
                state: String,
            ) {
                states += role
            }

            override fun onVehicleFeed(values: Bundle) = Unit
        }

    private fun result(): IAutoResult =
        object : IAutoResult.Stub() {
            override fun onResult(value: Bundle) = Unit
        }
}
