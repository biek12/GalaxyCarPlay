package com.galaxy.auto.client.ipc

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.contract.IAutoCallback
import com.galaxy.auto.contract.IAutoResult
import com.galaxy.auto.contract.IAutoService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.FileInputStream
import java.time.Duration
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 连接、握手、恢复与关闭的真实行为用例。
 *
 * 只替换系统绑定与远端接口：走真实AutoClient公开API、真实Handler/ServiceConnection和
 * IAutoService.Stub.asInterface；本地服务是记录型Stub，不启动AutoService、HTTP或Root。
 * [RecordingService]与companion中的推进夹具供其余三套共用，避免复制第二份连接模型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AutoClientConnectionTest {
    /** 每个用例结束都用生产close退出自有worker，失败后的强制清理不计为通过依据。 */
    @After
    fun releaseClientWorkers() = releaseClients()

    /** 记录型本地远端：按顺序保存动作与参数，可读取FD，可回放结果与注入异常。 */
    internal class RecordingService(
        @Volatile var version: Int = AutoContract.VERSION,
    ) : IAutoService.Stub() {
        data class Call(
            val name: String,
            val args: List<Any?>,
        )

        val calls = CopyOnWriteArrayList<Call>()
        val results = CopyOnWriteArrayList<IAutoResult>()
        val fdText = CopyOnWriteArrayList<String>()
        val heldFd = CopyOnWriteArrayList<ParcelFileDescriptor>()
        val fdReads = AtomicInteger()

        @Volatile
        var callback: IAutoCallback? = null

        @Volatile
        var attachedOwner: IBinder? = null

        @Volatile
        var detachedOwner: IBinder? = null

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

        /**
         * 调用期间读取只读FD的真实字节，读完才允许记录方法名。
         * 不关闭fd，所有权仍在客户端的use；测试必须等[fdReads]而不是只看到方法名。
         */
        private fun read(fd: ParcelFileDescriptor) {
            heldFd.add(fd)
            fdText.add(String(FileInputStream(fd.fileDescriptor).readBytes(), Charsets.UTF_8))
            fdReads.incrementAndGet()
        }

        override fun getProtocolVersion(): Int {
            record("protocolVersion")
            return version
        }

        override fun getSnapshot() = Bundle()

        override fun getVehicleCatalog(
            offset: Int,
            limit: Int,
        ): Bundle {
            record("getVehicleCatalog", offset, limit)
            return Bundle()
        }

        override fun getVehicleProperties(
            offset: Int,
            limit: Int,
        ): Bundle {
            record("getVehicleProperties", offset, limit)
            return Bundle()
        }

        override fun getVehicleValue(
            propertyId: Int,
            areaId: Int,
            characterOffset: Int,
        ): Bundle {
            record("getVehicleValue", propertyId, areaId, characterOffset)
            return Bundle()
        }

        override fun attach(
            owner: IBinder,
            role: String,
            callback: IAutoCallback,
        ) {
            record("attach", owner, role, callback)
            attachedOwner = owner
            this.callback = callback
        }

        override fun detach(owner: IBinder) {
            record("detach", owner)
            detachedOwner = owner
        }

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
        ) {
            read(fd)
            record("publishArtwork", owner, fd)
        }

        override fun executeShell(
            owner: IBinder,
            transport: String,
            command: String,
            timeoutMillis: Int,
            result: IAutoResult,
        ) {
            record("executeShell", owner, transport, command, timeoutMillis, result)
            results.add(result)
        }

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

        override fun getVehicleValues(keys: Array<String>?): Bundle {
            record("getVehicleValues", keys?.toList())
            return Bundle()
        }

        override fun setVehicleScan(enabled: Boolean) = record("setVehicleScan", enabled)

        override fun requestClientPermissions(
            owner: IBinder,
            permissions: Array<String>?,
            result: IAutoResult,
        ) {
            record("requestClientPermissions", owner, permissions?.toList(), result)
            results.add(result)
        }

        override fun configureSentinel(
            owner: IBinder,
            enabled: Boolean,
            result: IAutoResult,
        ) {
            record("configureSentinel", owner, enabled, result)
            results.add(result)
        }

        override fun reportSentinelIdle(
            owner: IBinder,
            powerSequence: Long,
        ) = record("reportSentinelIdle", owner, powerSequence)

        override fun vehicleWebRequest(
            owner: IBinder,
            action: String,
            payload: Bundle,
            result: IAutoResult,
        ) {
            record("vehicleWebRequest", owner, action, payload, result)
            results.add(result)
        }

        override fun vehicleWebUpload(
            owner: IBinder,
            clipId: String,
            direction: String,
            file: ParcelFileDescriptor,
            length: Long,
            result: IAutoResult,
        ) {
            read(file)
            record("vehicleWebUpload", owner, clipId, direction, file, length, result)
            results.add(result)
        }

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
        ) {
            read(fd)
            record("publishTrackArtwork", owner, track, fd)
        }

        override fun prepareOemArtwork(
            owner: IBinder,
            fd: ParcelFileDescriptor,
            result: IAutoResult,
        ) {
            read(fd)
            record("prepareOemArtwork", owner, fd, result)
            results.add(result)
        }

        override fun configureClusterImage(
            owner: IBinder,
            configuration: Bundle,
            image: ParcelFileDescriptor,
            result: IAutoResult,
        ) {
            read(image)
            record("configureClusterImage", owner, configuration, image, result)
            results.add(result)
        }
    }

    private fun client(
        service: RecordingService,
        role: String = AutoContract.OBSERVER,
        onState: (String) -> Unit = {},
        onReady: () -> Unit = {},
        onSnapshot: (Bundle) -> Unit = {},
        onCommand: (Int) -> Unit = {},
        onVehicleFeed: (Bundle) -> Unit = {},
    ): AutoClient =
        createClient(
            service,
            role = role,
            onState = onState,
            onReady = onReady,
            onSnapshot = onSnapshot,
            onCommand = onCommand,
            onVehicleFeed = onVehicleFeed,
        )

    @Test
    fun `connect与close都要求主线程且构造不触发绑定`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val service = RecordingService()
        val subject = createClient(service, role = AutoContract.OBSERVER)
        try {
            assertTrue(service.names().isEmpty())
            assertEquals(0, startedCount(app))
            assertEquals(0, shadowOf(app).boundServiceConnections.size)
            assertTrue(offMain { subject.connect() } is IllegalStateException)
            assertTrue(offMain { subject.close() } is IllegalStateException)
            subject.connect()
            settle { service.names().contains("attach") }
            // Robolectric把startForegroundService与bindService都记入started service队列。
            assertTrue(startedCount(app) >= 1)
        } finally {
            subject.close()
        }
        assertTrue(offMain { subject.close() } is IllegalStateException)
    }

    @Test
    fun `握手按版本attach元素active订阅flush再ready且owner为同一代租约`() {
        val states = CopyOnWriteArrayList<String>()
        var ready = 0
        val service = RecordingService()
        val subject =
            client(
                service,
                role = AutoContract.ELEMENTS,
                onState = { states.add(it) },
                onReady = { ready++ },
            )
        try {
            subject.setActive(true)
            subject.configureClusterElements(
                ClusterDisplayConfiguration(mode = ClusterDisplayConfiguration.OEM_ELEMENTS),
            )
            subject.setVehicleFeed(listOf("gear", "displaySpeed"), 500)
            subject.publishNavigation(Bundle().apply { putString("road", "环路") })
            subject.connect()
            settle { ready == 1 }
            val names = service.names()
            val version = names.indexOf("protocolVersion")
            val attach = names.indexOf("attach")
            val elements = names.indexOf("configureClusterElements")
            val active = names.indexOf("setActive")
            val feed = names.indexOf("setVehicleFeed")
            val navigation = names.indexOf("publishNavigation")
            assertTrue(version >= 0 && attach > version)
            assertTrue(elements > attach && active > elements && feed > active && navigation > feed)
            assertEquals(listOf("displaySpeed", "gear"), service.last("setVehicleFeed").args[1])
            assertEquals(500, service.last("setVehicleFeed").args[2])
            val owner = service.attachedOwner
            assertNotNull(owner)
            assertSame(owner, service.last("setActive").args[0])
            assertSame(owner, service.last("publishNavigation").args[0])
            assertTrue(states.isEmpty())
        } finally {
            subject.close()
        }
    }

    @Test
    fun `协议版本不匹配不发出ready也不完成attach`() {
        val states = CopyOnWriteArrayList<String>()
        var ready = 0
        val service = RecordingService(version = AutoContract.VERSION - 1)
        val subject = client(service, onState = { states.add(it) }, onReady = { ready++ })
        try {
            subject.connect()
            settle { states.isNotEmpty() }
            assertEquals(0, ready)
            assertFalse(service.names().contains("attach"))
            assertTrue(states.any { it.startsWith("connect:") })
        } finally {
            subject.close()
        }
    }

    @Test
    fun `普通断开只通报状态而null与died各追加一次恢复`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val states = CopyOnWriteArrayList<String>()
        val service = RecordingService()
        val subject = client(service, onState = { states.add(it) })
        try {
            subject.connect()
            settle { service.names().contains("attach") }
            val connection = shadowOf(app).boundServiceConnections.single()
            startedCount(app)
            connection.onServiceDisconnected(SERVICE_NAME)
            pump()
            assertEquals(listOf("service_disconnected"), states.toList())
            assertEquals(0, startedCount(app))

            connection.onNullBinding(SERVICE_NAME)
            pump()
            assertTrue(states.contains("binding_rejected"))
            connection.onBindingDied(SERVICE_NAME)
            pump()
            assertTrue(states.contains("binding_died"))
        } finally {
            subject.close()
        }
    }

    @Test
    fun `绑定失败按1秒2秒4秒三次恢复且没有第四次`() {
        val app = RuntimeEnvironment.getApplication() as Application
        shadowOf(app).declareComponentUnbindable(SERVICE_NAME)
        var ready = 0
        val subject = createClient(RecordingService(), role = AutoContract.OBSERVER, onReady = { ready++ })
        try {
            subject.connect()
            pump()
            val each = startedCount(app)
            assertTrue(each >= 1)
            advance(999)
            assertEquals(0, startedCount(app))
            advance(1)
            assertEquals(each, startedCount(app))
            advance(1999)
            assertEquals(0, startedCount(app))
            advance(1)
            assertEquals(each, startedCount(app))
            advance(3999)
            assertEquals(0, startedCount(app))
            advance(1)
            assertEquals(each, startedCount(app))
            advance(8000)
            assertEquals(0, startedCount(app))
            assertEquals(0, ready)
        } finally {
            subject.close()
        }
    }

    @Test
    fun `bindService抛异常时记录bind状态并进入同一恢复队列`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val states = CopyOnWriteArrayList<String>()
        val shadow = shadowOf(app)
        shadow.setThrowInBindService(SecurityException("denied"))
        val subject = createClient(RecordingService(), role = AutoContract.OBSERVER, onState = { states.add(it) })
        try {
            subject.connect()
            pump()
            assertTrue(states.contains("bind:SecurityException"))
            assertTrue(shadow.nextStartedService != null)
        } finally {
            subject.close()
        }
    }

    @Test
    fun `close用旧lease解绑幂等且自有worker真实退出`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val states = CopyOnWriteArrayList<String>()
        val snapshots = CopyOnWriteArrayList<Bundle>()
        val commands = CopyOnWriteArrayList<Int>()
        val feeds = CopyOnWriteArrayList<Bundle>()
        var ready = 0
        val service = RecordingService()
        val subject =
            client(
                service,
                onState = { states.add(it) },
                onReady = { ready++ },
                onSnapshot = { snapshots.add(it) },
                onCommand = { commands.add(it) },
                onVehicleFeed = { feeds.add(it) },
            )
        val worker = workerOf(subject)
        try {
            subject.connect()
            settle { ready == 1 && service.callback != null }
            proveClientWorker(subject, service.callback!!)
            val attached = service.attachedOwner
            subject.close()
            subject.close()
            service.callback!!.onSnapshot(Bundle().apply { putString("late", "snapshot") })
            service.callback!!.onSnapshot(Bundle().apply { putInt("command", 9) })
            service.callback!!.onProjectionState("cluster", "late-projection")
            service.callback!!.onVehicleFeed(Bundle().apply { putString("gear", "R") })
            settle { service.detachedOwner != null && !worker.isAlive }
            assertSame(attached, service.detachedOwner)
            assertEquals(0, shadowOf(app).boundServiceConnections.size)
            assertEquals(1, service.names().count { it == "detach" })
            assertEquals(1, service.names().count { it == "attach" })
            assertTrue(snapshots.isEmpty())
            assertTrue(commands.isEmpty())
            assertTrue(feeds.isEmpty())
            assertFalse(states.contains("late-projection"))
            assertEquals(1, ready)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9000L))
            pump()
            assertEquals(1, service.names().count { it == "attach" })
            assertEquals(1, service.names().count { it == "detach" })
            assertEquals(1, ready)
            assertTrue(states.isEmpty())
        } finally {
            if (worker.isAlive) worker.quit()
        }
    }

    @Test
    fun `恢复成功后重试归零且重连使用新owner关闭只解绑当时lease`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val service = RecordingService()
        var ready = 0
        val subject = client(service, onReady = { ready++ })
        val worker = workerOf(subject)
        try {
            subject.connect()
            settle { ready == 1 && service.callback != null }
            proveClientWorker(subject, service.callback!!)
            val link = connection()
            val owner1 = service.attachedOwner
            startedCount(app)

            link.onBindingDied(SERVICE_NAME)
            advance(999)
            assertEquals(1, ready)
            assertEquals(0, startedCount(app))
            advance(1)
            settle { ready == 2 }
            val owner2 = service.attachedOwner
            assertNotSame(owner1, owner2)
            startedCount(app)

            link.onBindingDied(SERVICE_NAME)
            advance(999)
            assertEquals(2, ready)
            assertEquals(0, startedCount(app))
            advance(1)
            settle { ready == 3 }
            val owner3 = service.attachedOwner
            assertNotSame(owner2, owner3)

            shadowOf(app).setThrowInBindService(SecurityException("a2-test-denied"))
            startedCount(app)
            link.onBindingDied(SERVICE_NAME)
            advance(999)
            assertEquals(0, startedCount(app))
            advance(1)
            assertTrue(startedCount(app) >= 1)
            advance(1999)
            assertEquals(0, startedCount(app))
            advance(1)
            assertTrue(startedCount(app) >= 1)
            advance(3999)
            assertEquals(0, startedCount(app))
            advance(1)
            assertTrue(startedCount(app) >= 1)
            advance(8000)
            assertEquals(0, startedCount(app))
            assertEquals(3, ready)

            shadowOf(app).setThrowInBindService(null)
            subject.connect()
            settle { ready == 4 }
            val owner4 = service.attachedOwner
            assertNotSame(owner3, owner4)
            val attaches = service.names().count { it == "attach" }
            subject.close()
            subject.close()
            settle { service.detachedOwner === owner4 && !worker.isAlive }
            assertEquals(1, service.names().count { it == "detach" })
            assertEquals(attaches, service.names().count { it == "attach" })
            advance(8000)
            assertEquals(attaches, service.names().count { it == "attach" })
            assertEquals(1, service.names().count { it == "detach" })
            assertEquals(4, ready)
        } finally {
            if (worker.isAlive) worker.quit()
        }
    }

    /** 从真实非Looper线程调用，返回捕获到的异常。 */
    private fun offMain(block: () -> Unit): Throwable? {
        val captured = AtomicReference<Throwable?>()
        val thread =
            Thread {
                try {
                    block()
                } catch (error: Throwable) {
                    captured.set(error)
                }
            }
        thread.start()
        thread.join(5000)
        return captured.get()
    }

    private fun startedCount(app: Application): Int {
        val shadow = shadowOf(app)
        var count = 0
        while (shadow.nextStartedService != null) count++
        return count
    }

    /** 只推进main与已绑定到客户端实例的worker，执行错误直接抛出。 */
    private fun pump() {
        pumpAll()
    }

    /** 有界次数推进直到真实行为发生，不依赖Robolectric下的虚拟时钟。 */
    private fun settle(done: () -> Boolean) {
        repeat(300) {
            if (done()) return
            pump()
            if (done()) return
            Thread.sleep(10)
        }
        assertTrue("真实异步行为未完成", done())
    }

    companion object {
        internal val SERVICE_NAME = ComponentName(AutoContract.SERVICE_PACKAGE, AutoContract.SERVICE_CLASS)

        private const val WORKER_PROBE = "a2-worker-probe"
        private val workers = IdentityHashMap<AutoClient, HandlerThread>()
        private val workerProbes = IdentityHashMap<AutoClient, AtomicReference<HandlerThread?>>()
        private val feedOnWorker = IdentityHashMap<AutoClient, Boolean>()

        /**
         * 构造真实客户端，并把本次启动的worker绑定到该实例。
         *
         * 只取构造前后HandlerThread集合的差，避免按全局同名线程任选一个。
         * 随后可用[proveClientWorker]经vehicleFeedOnWorker回调确认就是这条线程。
         */
        internal fun createClient(
            service: IBinder,
            role: String = AutoContract.OBSERVER,
            onSurface: (Surface?) -> Unit = {},
            onSnapshot: (Bundle) -> Unit = {},
            onState: (String) -> Unit = {},
            onCommand: (Int) -> Unit = {},
            onConfiguredSurface: (Surface?, Long) -> Unit = { _, _ -> },
            onVehicleFeed: (Bundle) -> Unit = {},
            onReady: () -> Unit = {},
            replayVehicleFeedOnConnect: Boolean = true,
            vehicleFeedOnWorker: Boolean = true,
        ): AutoClient {
            shadowOf(app()).setComponentNameAndServiceForBindService(SERVICE_NAME, service)
            val seen = AtomicReference<HandlerThread?>()
            val before = handlerThreads()
            val client =
                AutoClient(
                    app(),
                    role,
                    onSurface = onSurface,
                    onSnapshot = onSnapshot,
                    onState = onState,
                    onCommand = onCommand,
                    onConfiguredSurface = onConfiguredSurface,
                    onVehicleFeed = { bundle ->
                        if (bundle.getBoolean(WORKER_PROBE)) {
                            (Looper.myLooper()?.thread as? HandlerThread)?.let(seen::set)
                        } else {
                            onVehicleFeed(bundle)
                        }
                    },
                    onReady = onReady,
                    replayVehicleFeedOnConnect = replayVehicleFeedOnConnect,
                    vehicleFeedOnWorker = vehicleFeedOnWorker,
                )
            val created = handlerThreads().filter { it !in before }
            check(created.size == 1) {
                "本次构造应只启动被测客户端自己的worker，实际=${created.map { it.name }}"
            }
            val worker = created.single()
            check(worker.name == "AutoClient-$role") { "worker=${worker.name}" }
            workers[client] = worker
            workerProbes[client] = seen
            feedOnWorker[client] = vehicleFeedOnWorker
            return client
        }

        /** 被测客户端自己的worker；未登记说明没有走[createClient]。 */
        internal fun workerOf(client: AutoClient): HandlerThread =
            workers[client] ?: error("客户端worker未绑定到本次实例")

        /** 只关闭本用例登记的客户端；先断言生产退出，最后才允许失败清理。 */
        internal fun releaseClients() {
            val owned = workers.toMap()
            try {
                for ((client, worker) in owned) {
                    client.close()
                    idleWorker(worker)
                    worker.join(1000)
                    assertFalse("生产close未退出${worker.name}", worker.isAlive)
                }
            } finally {
                for (worker in owned.values.filter { it.isAlive }) {
                    worker.quit()
                    worker.join(1000)
                }
                workers.clear()
                workerProbes.clear()
                feedOnWorker.clear()
            }
        }

        /**
         * 用真实vehicleFeed回调确认实例线程。vehicleFeedOnWorker=false时回调在main，不把它当成worker。
         */
        internal fun proveClientWorker(
            client: AutoClient,
            callback: IAutoCallback,
        ) {
            if (feedOnWorker[client] != true) return
            callback.onVehicleFeed(Bundle().apply { putBoolean(WORKER_PROBE, true) })
            idleWorker(workerOf(client))
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(workerOf(client), workerProbes.getValue(client).get())
        }

        /**
         * 只idle这一条已绑定worker。
         * 已由生产quit的looper不再接受Handler消息；以真实post结果识别这场退出竞态，
         * 不用PAUSED模式不支持的hasQuit，也不按异常文本猜测执行错误。
         */
        internal fun idleWorker(worker: HandlerThread) {
            if (!worker.isAlive) return
            val looper = worker.looper ?: return
            val shadow = shadowOf(looper)
            val handler = Handler(looper)
            if (!handler.post {}) return
            try {
                shadow.idle()
            } catch (error: IllegalStateException) {
                if (handler.post {}) throw error
            }
        }

        /** 供其余三套复用：main与该实例worker各推进一次，worker队列默认暂停需显式idle。 */
        internal fun pumpAll() {
            shadowOf(Looper.getMainLooper()).idle()
            for (worker in workers.values.toList()) {
                idleWorker(worker)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

        private fun handlerThreads(): List<HandlerThread> =
            Thread.getAllStackTraces().keys.mapNotNull { it as? HandlerThread }

        /** 供其余三套复用的有界等待，不依赖虚拟时钟。 */
        internal fun await(done: () -> Boolean) {
            repeat(300) {
                if (done()) return
                pumpAll()
                if (done()) return
                Thread.sleep(10)
            }
            assertTrue("真实异步行为未完成", done())
        }

        /** 供其余三套复用的主Looper时间推进。 */
        internal fun advance(millis: Long) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
            pumpAll()
        }

        /** 供其余三套复用的连接夹具。 */
        internal fun bind(service: RecordingService) {
            val app = RuntimeEnvironment.getApplication() as Application
            shadowOf(app).setComponentNameAndServiceForBindService(SERVICE_NAME, service)
        }

        /** 供其余三套复用的真实ServiceConnection。 */
        internal fun connection() =
            shadowOf(RuntimeEnvironment.getApplication() as Application).boundServiceConnections.single()

        internal fun app(): Application = RuntimeEnvironment.getApplication() as Application
    }
}
