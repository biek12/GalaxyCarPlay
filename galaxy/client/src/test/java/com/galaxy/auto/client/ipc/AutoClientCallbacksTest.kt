package com.galaxy.auto.client.ipc

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoCallback
import com.galaxy.auto.contract.IAutoResult
import com.galaxy.auto.contract.IAutoService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 入站回调与两种Surface的真实线程、代次与释放次序用例。
 *
 * 本套件完全自包含：只替换系统绑定与远端接口，走真实AutoClient公开API、真实
 * Handler/ServiceConnection与IAutoService.Stub.asInterface；不接任何显示后端。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AutoClientCallbacksTest {
    private data class Call(
        val name: String,
        val args: List<Any?>,
    )

    /** 记录型本地远端：只替换远端IO，不接真实服务、车辆或显示后端。 */
    private class LocalService : IAutoService.Stub() {
        val calls = CopyOnWriteArrayList<Call>()

        @Volatile
        var callback: IAutoCallback? = null

        @Volatile
        var version: Int = AutoContract.VERSION

        @Volatile
        var throwOn: String? = null

        fun names(): List<String> = calls.map { it.name }

        fun last(name: String): Call = calls.last { it.name == name }

        private fun record(
            name: String,
            vararg args: Any?,
        ) {
            calls.add(Call(name, args.toList()))
            if (throwOn == name) throw IllegalStateException("remote_failure")
        }

        override fun getProtocolVersion(): Int {
            record("protocolVersion")
            return version
        }

        override fun getSnapshot() = Bundle()

        override fun getVehicleCatalog(
            offset: Int,
            limit: Int,
        ) = Bundle()

        override fun getVehicleProperties(
            offset: Int,
            limit: Int,
        ) = Bundle()

        override fun getVehicleValue(
            propertyId: Int,
            areaId: Int,
            characterOffset: Int,
        ) = Bundle()

        override fun attach(
            owner: IBinder,
            role: String,
            callback: IAutoCallback,
        ) {
            record("attach", owner, role, callback)
            this.callback = callback
        }

        override fun detach(owner: IBinder) = record("detach", owner)

        override fun setActive(
            owner: IBinder,
            active: Boolean,
        ) = record("setActive", owner, active)

        override fun publishMedia(
            owner: IBinder,
            value: Bundle,
        ) = record("publishMedia", owner, value)

        override fun publishNavigation(
            owner: IBinder,
            value: Bundle,
        ) = record("publishNavigation", owner, value)

        override fun publishArtwork(
            owner: IBinder,
            fd: ParcelFileDescriptor,
        ) = record("publishArtwork", owner, fd)

        override fun executeShell(
            owner: IBinder,
            transport: String,
            command: String,
            timeoutMillis: Int,
            result: IAutoResult,
        ) = record("executeShell", owner, transport, command, timeoutMillis, result)

        override fun mediaCommand(
            owner: IBinder,
            command: Int,
        ) = record("mediaCommand", owner, command)

        override fun configureScene(
            owner: IBinder,
            value: Bundle,
        ) = record("configureScene", owner, value)

        override fun setPreviewSurface(
            owner: IBinder,
            surface: Surface?,
        ) = record("setPreviewSurface", owner, surface)

        override fun setProjectionPolicy(
            owner: IBinder,
            value: Bundle,
        ) = record("setProjectionPolicy", owner, value)

        override fun setVehicleFeed(
            owner: IBinder,
            keys: Array<String>?,
            minIntervalMillis: Int,
        ) = record("setVehicleFeed", owner, keys?.toList(), minIntervalMillis)

        override fun getVehicleValues(keys: Array<String>?) = Bundle()

        override fun setVehicleScan(enabled: Boolean) = record("setVehicleScan", enabled)

        override fun requestClientPermissions(
            owner: IBinder,
            permissions: Array<String>?,
            result: IAutoResult,
        ) = record("requestClientPermissions", owner, permissions?.toList(), result)

        override fun configureSentinel(
            owner: IBinder,
            enabled: Boolean,
            result: IAutoResult,
        ) = record("configureSentinel", owner, enabled, result)

        override fun reportSentinelIdle(
            owner: IBinder,
            powerSequence: Long,
        ) = record("reportSentinelIdle", owner, powerSequence)

        override fun vehicleWebRequest(
            owner: IBinder,
            action: String,
            payload: Bundle,
            result: IAutoResult,
        ) = record("vehicleWebRequest", owner, action, payload, result)

        override fun vehicleWebUpload(
            owner: IBinder,
            clipId: String,
            direction: String,
            file: ParcelFileDescriptor,
            length: Long,
            result: IAutoResult,
        ) = record("vehicleWebUpload", owner, clipId, direction, file, length, result)

        override fun configureClusterElements(
            owner: IBinder,
            configuration: Bundle,
        ) = record("configureClusterElements", owner, configuration)

        override fun publishVisualMedia(
            owner: IBinder,
            value: Bundle,
        ) = record("publishVisualMedia", owner, value)

        override fun publishTrackArtwork(
            owner: IBinder,
            track: String,
            fd: ParcelFileDescriptor,
        ) = record("publishTrackArtwork", owner, track, fd)

        override fun prepareOemArtwork(
            owner: IBinder,
            fd: ParcelFileDescriptor,
            result: IAutoResult,
        ) = record("prepareOemArtwork", owner, fd, result)

        override fun configureClusterImage(
            owner: IBinder,
            configuration: Bundle,
            image: ParcelFileDescriptor,
            result: IAutoResult,
        ) = record("configureClusterImage", owner, configuration, image, result)
    }

    /** 与生产duplicateSurface同一条Parcel路径创建Surface，避免SurfaceTexture注册常驻IdleHandler。 */
    private fun newSurface(): Surface {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeString("a2-surface")
            parcel.setDataPosition(0)
            Surface.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun bind(service: LocalService) {
        shadowOf(RuntimeEnvironment.getApplication() as Application)
            .setComponentNameAndServiceForBindService(SERVICE_NAME, service)
    }

    /** main与存活worker各推进一次；worker队列在PAUSED下默认等待，必须显式idle。 */
    private fun pump() {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.getAllStackTraces().keys.filterIsInstance<HandlerThread>().forEach { thread ->
            if (thread.isAlive) runCatching { shadowOf(thread.looper).idle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 有界次数等待，不依赖Robolectric下的虚拟时钟推进。 */
    private fun waitFor(done: () -> Boolean) {
        repeat(300) {
            if (done()) return
            pump()
            if (done()) return
            Thread.sleep(10)
        }
        assertTrue("真实异步行为未完成", done())
    }

    private fun connected(
        service: LocalService,
        role: String = AutoContract.OBSERVER,
        onSurface: (Surface?) -> Unit = {},
        onSnapshot: (Bundle) -> Unit = {},
        onCommand: (Int) -> Unit = {},
        onConfiguredSurface: (Surface?, Long) -> Unit = { _, _ -> },
        onVehicleFeed: (Bundle) -> Unit = {},
        vehicleFeedOnWorker: Boolean = false,
    ): AutoClient {
        bind(service)
        val subject =
            AutoClient(
                RuntimeEnvironment.getApplication(),
                role,
                onSurface = onSurface,
                onSnapshot = onSnapshot,
                onCommand = onCommand,
                onConfiguredSurface = onConfiguredSurface,
                onVehicleFeed = onVehicleFeed,
                vehicleFeedOnWorker = vehicleFeedOnWorker,
            )
        subject.connect()
        waitFor { service.callback != null }
        return subject
    }

    @Test
    fun `snapshot走普通回调而command走命令分支且Bundle设classLoader`() {
        val snapshots = CopyOnWriteArrayList<Bundle>()
        val commands = CopyOnWriteArrayList<Int>()
        val service = LocalService()
        val subject = connected(service, onSnapshot = { snapshots.add(it) }, onCommand = { commands.add(it) })
        try {
            service.callback!!.onSnapshot(Bundle().apply { putString("road", "环路") })
            waitFor { snapshots.isNotEmpty() }
            assertEquals("环路", snapshots.single().getString("road"))
            assertNotNull(snapshots.single().classLoader)
            service.callback!!.onSnapshot(Bundle().apply { putInt("command", 7) })
            waitFor { commands.isNotEmpty() }
            assertEquals(listOf(7), commands.toList())
            assertEquals(1, snapshots.size)
        } finally {
            subject.close()
        }
    }

    @Test
    fun `有效Surface换代解析epoch并释放上一代接收引用`() {
        val surfaces = CopyOnWriteArrayList<Surface?>()
        val epochs = CopyOnWriteArrayList<Long>()
        val service = LocalService()
        val subject =
            connected(
                service,
                role = AutoContract.THEME,
                onSurface = { surfaces.add(it) },
                onConfiguredSurface = { _, epoch -> epochs.add(epoch) },
            )
        try {
            val first = newSurface()
            service.callback!!.onSurface("cluster@12345", first)
            waitFor { epochs.isNotEmpty() }
            assertSame(first, surfaces.last())
            assertEquals(12345L, epochs.last())

            val second = newSurface()
            service.callback!!.onSurface("cluster", second)
            waitFor { epochs.size == 2 }
            assertSame(second, surfaces.last())
            assertEquals(0L, epochs.last())
            assertFalse(first.isValid)
            assertTrue(second.isValid)
        } finally {
            subject.close()
        }
    }

    @Test
    fun `断线后旧代传入的Surface必须先释放且不回调`() {
        val surfaces = CopyOnWriteArrayList<Surface?>()
        val service = LocalService()
        val subject = connected(service, role = AutoContract.THEME, onSurface = { surfaces.add(it) })
        try {
            shadowOf(RuntimeEnvironment.getApplication() as Application)
                .boundServiceConnections
                .single()
                .onServiceDisconnected(SERVICE_NAME)
            pump()
            val before = surfaces.size
            val stale = newSurface()
            service.callback!!.onSurface("cluster@1", stale)
            pump()
            assertFalse(stale.isValid)
            assertEquals(before, surfaces.size)
        } finally {
            subject.close()
        }
    }

    @Test
    fun `surface_closed与lost与close的borrowed释放次序确实不同`() {
        // configured回调触发时，surface_closed尚未release；lost/close则已经release。
        fun observed(trigger: (AutoClient, LocalService) -> Unit): Boolean {
            var borrowed: Surface? = null
            var validAtConfigured: Boolean? = null
            val service = LocalService()
            val subject =
                connected(
                    service,
                    role = AutoContract.THEME,
                    onSurface = { if (it != null) borrowed = it },
                    onConfiguredSurface = { _, _ -> validAtConfigured = borrowed?.isValid },
                )
            try {
                val owned = newSurface()
                service.callback!!.onSurface("cluster@1", owned)
                waitFor { borrowed === owned }
                validAtConfigured = null
                trigger(subject, service)
                pump()
                assertFalse(owned.isValid)
                return validAtConfigured ?: error("configured回调未执行")
            } finally {
                subject.close()
            }
        }

        val surfaceClosed =
            observed { _, remote ->
                remote.callback!!.onProjectionState("cluster", "surface_closed")
            }
        val lost =
            observed { _, _ ->
                shadowOf(RuntimeEnvironment.getApplication() as Application)
                    .boundServiceConnections
                    .single()
                    .onServiceDisconnected(SERVICE_NAME)
            }
        val closed = observed { client, _ -> client.close() }
        assertTrue("surface_closed应在configured之后release", surfaceClosed)
        assertFalse("lost应在configured之前release", lost)
        assertFalse("close应在configured之前release", closed)
    }

    @Test
    fun `vehicleFeedOnWorker两个取值真实决定回调线程`() {
        for (onWorker in listOf(false, true)) {
            val onMain = CopyOnWriteArrayList<Boolean>()
            val service = LocalService()
            val subject =
                connected(
                    service,
                    onVehicleFeed = { onMain.add(Looper.myLooper() == Looper.getMainLooper()) },
                    vehicleFeedOnWorker = onWorker,
                )
            try {
                service.callback!!.onVehicleFeed(Bundle().apply { putString("gear", "D") })
                waitFor { onMain.isNotEmpty() }
                assertEquals(!onWorker, onMain.single())
            } finally {
                subject.close()
            }
        }
    }

    @Test
    fun `PREVIEW复制独立引用替换释放旧预览null清空且close释放`() {
        val service = LocalService()
        val subject = connected(service, role = AutoContract.PREVIEW)
        try {
            val original = newSurface()
            subject.setPreviewSurface(original)
            waitFor { service.names().contains("setPreviewSurface") }
            val firstCopy = service.last("setPreviewSurface").args[1] as Surface
            assertNotSame(original, firstCopy)
            assertTrue(original.isValid)

            subject.setPreviewSurface(newSurface())
            waitFor { service.names().count { it == "setPreviewSurface" } == 2 }
            assertFalse(firstCopy.isValid)
            val secondCopy = service.last("setPreviewSurface").args[1] as Surface
            assertTrue(secondCopy.isValid)

            subject.setPreviewSurface(null)
            waitFor { service.names().count { it == "setPreviewSurface" } == 3 }
            assertNull(service.last("setPreviewSurface").args[1])
            assertFalse(secondCopy.isValid)

            subject.setPreviewSurface(newSurface())
            waitFor { service.names().count { it == "setPreviewSurface" } == 4 }
            val lastCopy = service.last("setPreviewSurface").args[1] as Surface
            subject.close()
            pump()
            assertFalse(lastCopy.isValid)
        } finally {
            subject.close()
        }
    }

    @Test
    fun `非PREVIEW角色拒绝预览而io投递失败时原Surface不受影响`() {
        val mapService = LocalService()
        bind(mapService)
        val mapClient = AutoClient(RuntimeEnvironment.getApplication(), AutoContract.MAP)
        val denied = runCatching { mapClient.setPreviewSurface(newSurface()) }.exceptionOrNull()
        assertTrue(denied is IllegalArgumentException)
        mapClient.close()

        // owner级补充：quit后的io使post返回false，生产分支只释放Parcel复制出的独立引用，
        // 不调用远端、不抛异常、也不释放调用方持有的原Surface。
        val app = RuntimeEnvironment.getApplication() as Application
        val dead = HandlerThread("A2-dead-io").apply { start() }
        val deadIo = Handler(dead.looper)
        dead.quit()
        assertFalse(deadIo.post {})
        val connection =
            ClientConnectionSession(app, AutoContract.PREVIEW, Handler(Looper.getMainLooper()), deadIo, {}, {})
        val dispatch =
            ClientCallbackDispatch(
                connection,
                AutoContract.PREVIEW,
                Handler(Looper.getMainLooper()),
                deadIo,
                null,
                false,
                {},
                {},
                {},
                {},
                { _, _ -> },
                {},
            )
        val original = newSurface()
        dispatch.setPreviewSurface(original) {}
        assertTrue(original.isValid)
    }

    private companion object {
        val SERVICE_NAME = ComponentName(AutoContract.SERVICE_PACKAGE, AutoContract.SERVICE_CLASS)
    }
}
