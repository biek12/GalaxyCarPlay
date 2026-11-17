package com.galaxy.auto.client.ipc

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
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
import java.util.concurrent.CopyOnWriteArrayList
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

        /** 调用期间读取只读FD的真实字节；不关闭fd，所有权仍在客户端的use。 */
        private fun read(fd: ParcelFileDescriptor) {
            heldFd.add(fd)
            fdText.add(String(FileInputStream(fd.fileDescriptor).readBytes(), Charsets.UTF_8))
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
            record("publishArtwork", owner, fd)
            read(fd)
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
            record("vehicleWebUpload", owner, clipId, direction, file, length, result)
            read(file)
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
            record("publishTrackArtwork", owner, track, fd)
            read(fd)
        }

        override fun prepareOemArtwork(
            owner: IBinder,
            fd: ParcelFileDescriptor,
            result: IAutoResult,
        ) {
            record("prepareOemArtwork", owner, fd, result)
            read(fd)
            results.add(result)
        }

        override fun configureClusterImage(
            owner: IBinder,
            configuration: Bundle,
            image: ParcelFileDescriptor,
            result: IAutoResult,
        ) {
            record("configureClusterImage", owner, configuration, image, result)
            read(image)
            results.add(result)
        }
    }

    private fun client(
        service: RecordingService,
        role: String = AutoContract.OBSERVER,
        onState: (String) -> Unit = {},
        onReady: () -> Unit = {},
    ): AutoClient {
        val app = RuntimeEnvironment.getApplication() as Application
        shadowOf(app).setComponentNameAndServiceForBindService(SERVICE_NAME, service)
        return AutoClient(app, role, onState = onState, onReady = onReady)
    }

    @Test
    fun `connect与close都要求主线程且构造不触发绑定`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val service = RecordingService()
        shadowOf(app).setComponentNameAndServiceForBindService(SERVICE_NAME, service)
        val subject = AutoClient(app, AutoContract.OBSERVER)
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
        val subject = AutoClient(app, AutoContract.OBSERVER, onReady = { ready++ })
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
        val subject = AutoClient(app, AutoContract.OBSERVER, onState = { states.add(it) })
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
        var ready = 0
        val service = RecordingService()
        val subject = client(service, onState = { states.add(it) }, onReady = { ready++ })
        subject.connect()
        settle { ready == 1 }
        val attached = service.attachedOwner
        val worker =
            Thread.getAllStackTraces().keys.firstOrNull { it.name == "AutoClient-${AutoContract.OBSERVER}" }
        assertNotNull(worker)
        subject.close()
        subject.close()
        settle { service.detachedOwner != null && !worker!!.isAlive }
        assertSame(attached, service.detachedOwner)
        assertEquals(0, shadowOf(app).boundServiceConnections.size)
        assertEquals(1, service.names().count { it == "detach" })
        assertEquals(1, service.names().count { it == "attach" })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9000L))
        pump()
        assertEquals(1, service.names().count { it == "attach" })
        assertEquals(1, service.names().count { it == "detach" })
        assertTrue(states.isEmpty())
    }

    /** 从真实非Looper线程调用，返回捕获到的异常。 */
    private fun offMain(block: () -> Unit): Throwable? {
        val captured = AtomicReference<Throwable?>()
        val thread = Thread {
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

    /** 推进main与所有真实AutoClient worker，不强制quit作为通过前提。 */
    private fun pump() {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.getAllStackTraces().keys.filterIsInstance<HandlerThread>().forEach { thread ->
            if (thread.isAlive) runCatching { shadowOf(thread.looper).idle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
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

        /** 供其余三套复用：main与存活worker各推进一次，worker队列默认暂停需显式idle。 */
        internal fun pumpAll() {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.getAllStackTraces().keys.filterIsInstance<HandlerThread>().forEach { thread ->
                if (thread.isAlive) runCatching { shadowOf(thread.looper).idle() }
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

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
