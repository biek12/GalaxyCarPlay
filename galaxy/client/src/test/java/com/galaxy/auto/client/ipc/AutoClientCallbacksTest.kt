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
import android.os.Parcelable
import android.view.Surface
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoCallback
import com.galaxy.auto.contract.IAutoResult
import com.galaxy.auto.contract.IAutoService
import org.junit.After
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 入站回调与两种Surface的真实线程、代次与释放次序用例。
 *
 * 本套件完全自包含：只替换系统绑定与远端接口，走真实AutoClient公开API、真实
 * Handler/ServiceConnection与IAutoService.Stub.asInterface；不接任何显示后端。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AutoClientCallbacksTest {
    /** 每次只收口本用例登记的客户端线程，避免下一场景借用遗留worker。 */
    @After
    fun releaseClientWorkers() = AutoClientConnectionTest.releaseClients()

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

    /** 只推进本次绑定的客户端worker；执行错误由共享夹具直接抛出。 */
    private fun pump() {
        AutoClientConnectionTest.pumpAll()
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
        onState: (String) -> Unit = {},
        onCommand: (Int) -> Unit = {},
        onConfiguredSurface: (Surface?, Long) -> Unit = { _, _ -> },
        onVehicleFeed: (Bundle) -> Unit = {},
        vehicleFeedOnWorker: Boolean = false,
    ): AutoClient {
        val subject =
            AutoClientConnectionTest.createClient(
                service,
                role = role,
                onSurface = onSurface,
                onSnapshot = onSnapshot,
                onState = onState,
                onCommand = onCommand,
                onConfiguredSurface = onConfiguredSurface,
                onVehicleFeed = onVehicleFeed,
                vehicleFeedOnWorker = vehicleFeedOnWorker,
            )
        subject.connect()
        waitFor { service.callback != null }
        AutoClientConnectionTest.proveClientWorker(subject, service.callback!!)
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
    fun `断线和close后传入的Surface都先释放且不回调`() {
        for (closed in listOf(false, true)) {
            val surfaces = CopyOnWriteArrayList<Surface?>()
            val service = LocalService()
            val subject = connected(service, role = AutoContract.THEME, onSurface = { surfaces.add(it) })
            try {
                if (closed) {
                    subject.close()
                } else {
                    shadowOf(RuntimeEnvironment.getApplication() as Application)
                        .boundServiceConnections
                        .single()
                        .onServiceDisconnected(SERVICE_NAME)
                }
                pump()
                val before = surfaces.size
                val stale = newSurface()
                assertTrue(stale.isValid)
                service.callback!!.onSurface("cluster@1", stale)
                pump()
                assertFalse(stale.isValid)
                assertEquals(before, surfaces.size)
            } finally {
                subject.close()
            }
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
        val inputs = mutableListOf<Surface>()
        try {
            waitFor { previewCalls(service).isNotEmpty() }
            val baseline = previewCalls(service).size
            assertNull(previewCalls(service).last().args[1])

            val original = newSurface().also { inputs.add(it) }
            subject.setPreviewSurface(original)
            val firstCopy = previewAt(service, baseline, 1).args[1] as Surface
            assertNotSame(original, firstCopy)
            assertTrue(original.isValid)
            assertTrue(firstCopy.isValid)

            val replacement = newSurface().also { inputs.add(it) }
            subject.setPreviewSurface(replacement)
            val secondCopy = previewAt(service, baseline, 2).args[1] as Surface
            assertNotSame(replacement, secondCopy)
            assertFalse(firstCopy.isValid)
            assertTrue(secondCopy.isValid)
            assertTrue(replacement.isValid)

            subject.setPreviewSurface(null)
            assertNull(previewAt(service, baseline, 3).args[1])
            assertFalse(secondCopy.isValid)

            val lastInput = newSurface().also { inputs.add(it) }
            subject.setPreviewSurface(lastInput)
            val lastCopy = previewAt(service, baseline, 4).args[1] as Surface
            assertNotSame(lastInput, lastCopy)
            assertTrue(lastCopy.isValid)
            subject.close()
            pump()
            assertFalse(lastCopy.isValid)
            assertTrue(lastInput.isValid)
        } finally {
            subject.close()
            inputs.filter { it.isValid }.forEach { it.release() }
        }
    }

    @Test
    fun `已排队的预览复制遇close被释放且不提交远端`() {
        val service = LocalService()
        val subject = connected(service, role = AutoContract.PREVIEW)
        val inputs = mutableListOf<Surface>()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            waitFor { previewCalls(service).isNotEmpty() }
            val baseline = previewCalls(service).size
            // 后台Looper可以即时运行；用真实队列屏障固定先排复制、再close、最后执行的顺序。
            assertTrue(
                Handler(AutoClientConnectionTest.workerOf(subject).looper).post {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS)) { "预览测试屏障未释放" }
                },
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val created =
                copiedSurfaces {
                    inputs.add(newSurface())
                    subject.setPreviewSurface(inputs.single())
                }
            val copy = created.single { it !== inputs.single() }
            assertEquals(baseline, previewCalls(service).size)
            assertTrue(copy.isValid)
            assertTrue(inputs.single().isValid)
            subject.close()
            resume.countDown()
            pump()
            assertFalse(copy.isValid)
            assertTrue(inputs.single().isValid)
            assertEquals(baseline, previewCalls(service).size)
        } finally {
            resume.countDown()
            subject.close()
            inputs.filter { it.isValid }.forEach { it.release() }
        }
    }

    @Test
    fun `非PREVIEW角色拒绝预览而io投递失败时原Surface不受影响`() {
        val mapService = LocalService()
        val mapClient = AutoClientConnectionTest.createClient(mapService, role = AutoContract.MAP)
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
        val created = copiedSurfaces { dispatch.setPreviewSurface(original) {} }
        val copy = created.single()
        assertNotSame(original, copy)
        assertFalse(copy.isValid)
        assertTrue(original.isValid)
        if (original.isValid) original.release()
    }

    @Test
    fun `旧代次与close后丢弃入站回调且有效路径核对线程与classLoader`() {
        for (kind in InboundKind.entries) {
            assertInboundAccepted(kind, vehicleFeedOnWorker = false)
            if (kind == InboundKind.FEED) assertInboundAccepted(kind, vehicleFeedOnWorker = true)
            assertInboundDiscarded(kind, close = false)
            assertInboundDiscarded(kind, close = true)
        }
    }

    private enum class InboundKind { SNAPSHOT, COMMAND, PROJECTION, FEED }

    private fun assertInboundAccepted(
        kind: InboundKind,
        vehicleFeedOnWorker: Boolean,
    ) {
        val snapshots = CopyOnWriteArrayList<Bundle>()
        val commands = CopyOnWriteArrayList<Int>()
        val states = CopyOnWriteArrayList<String>()
        val feeds = CopyOnWriteArrayList<Bundle>()
        val loopers = CopyOnWriteArrayList<Looper>()
        val service = LocalService()
        val subject =
            connected(
                service,
                onSnapshot = {
                    loopers.add(Looper.myLooper()!!)
                    snapshots.add(it)
                },
                onCommand = {
                    loopers.add(Looper.myLooper()!!)
                    commands.add(it)
                },
                onState = {
                    loopers.add(Looper.myLooper()!!)
                    states.add(it)
                },
                onVehicleFeed = {
                    loopers.add(Looper.myLooper()!!)
                    feeds.add(it)
                },
                vehicleFeedOnWorker = vehicleFeedOnWorker,
            )
        val bundle = Bundle()
        try {
            fireInbound(service.callback!!, kind, bundle)
            waitFor { loopers.isNotEmpty() }
            val expected =
                if (kind == InboundKind.FEED && vehicleFeedOnWorker) {
                    AutoClientConnectionTest.workerOf(subject).looper
                } else {
                    Looper.getMainLooper()
                }
            assertSame(expected, loopers.single())
            if (kind != InboundKind.PROJECTION) {
                assertEquals(AutoClient::class.java.classLoader, bundle.classLoader)
            }
            when (kind) {
                InboundKind.SNAPSHOT -> assertEquals("环路", snapshots.single().getString("road"))
                InboundKind.COMMAND -> assertEquals(listOf(3), commands.toList())
                InboundKind.PROJECTION -> assertEquals(listOf("visible"), states.toList())
                InboundKind.FEED -> assertEquals("D", feeds.single().getString("gear"))
            }
        } finally {
            subject.close()
        }
    }

    private fun assertInboundDiscarded(
        kind: InboundKind,
        close: Boolean,
    ) {
        val snapshots = CopyOnWriteArrayList<Bundle>()
        val commands = CopyOnWriteArrayList<Int>()
        val states = CopyOnWriteArrayList<String>()
        val feeds = CopyOnWriteArrayList<Bundle>()
        val service = LocalService()
        val subject =
            connected(
                service,
                onSnapshot = { snapshots.add(it) },
                onCommand = { commands.add(it) },
                onState = { states.add(it) },
                onVehicleFeed = { feeds.add(it) },
                vehicleFeedOnWorker = true,
            )
        try {
            val callback = service.callback!!
            if (close) {
                subject.close()
            } else {
                shadowOf(RuntimeEnvironment.getApplication() as Application)
                    .boundServiceConnections
                    .single()
                    .onServiceDisconnected(SERVICE_NAME)
                pump()
            }
            val before = snapshots.size + commands.size + feeds.size + states.count { it == "visible" || it == "late" }
            fireInbound(callback, kind, Bundle())
            pump()
            val after = snapshots.size + commands.size + feeds.size + states.count { it == "visible" || it == "late" }
            assertEquals(before, after)
        } finally {
            subject.close()
        }
    }

    private fun fireInbound(
        callback: IAutoCallback,
        kind: InboundKind,
        bundle: Bundle,
    ) {
        when (kind) {
            InboundKind.SNAPSHOT -> {
                bundle.putString("road", "环路")
                callback.onSnapshot(bundle)
            }
            InboundKind.COMMAND -> {
                bundle.putInt("command", 3)
                callback.onSnapshot(bundle)
            }
            InboundKind.PROJECTION -> callback.onProjectionState("cluster", "visible")
            InboundKind.FEED -> {
                bundle.putString("gear", "D")
                callback.onVehicleFeed(bundle)
            }
        }
    }

    private fun previewCalls(service: LocalService): List<Call> =
        service.calls.filter {
            it.name == "setPreviewSurface"
        }

    /** 等待握手基数之后的第[offset]次业务调用，并返回这一次而不是可能被后续调用覆盖的last。 */
    private fun previewAt(
        service: LocalService,
        baseline: Int,
        offset: Int,
    ): Call {
        waitFor { previewCalls(service).size == baseline + offset }
        return previewCalls(service)[baseline + offset - 1]
    }

    /** 观察Parcel复制出的Surface。生产私有字段不参与断言，观察结束后恢复系统CREATOR。 */
    private fun copiedSurfaces(block: () -> Unit): List<Surface> {
        val created = mutableListOf<Surface>()
        val field = Surface::class.java.getField("CREATOR")
        field.isAccessible = true
        val original = Surface.CREATOR
        val spy =
            object : Parcelable.Creator<Surface> {
                override fun createFromParcel(source: Parcel): Surface {
                    val surface = original.createFromParcel(source)
                    created.add(surface)
                    return surface
                }

                override fun newArray(size: Int): Array<Surface?> = original.newArray(size)
            }
        field.set(null, spy)
        try {
            block()
        } finally {
            field.set(null, original)
        }
        return created.toList()
    }

    private companion object {
        val SERVICE_NAME = ComponentName(AutoContract.SERVICE_PACKAGE, AutoContract.SERVICE_CLASS)
    }
}
