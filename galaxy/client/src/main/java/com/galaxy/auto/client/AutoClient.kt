package com.galaxy.auto.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.view.Surface
import com.galaxy.auto.contract.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/** 每客户端一代Binder租约。主线程只接生命周期，远端调用和封面pipe均在独立worker。 */
class AutoClient(context: Context, private val role: String,
    private val onSurface: (Surface?) -> Unit = {},
    private val onSnapshot: (Bundle) -> Unit = {},
    private val onState: (String) -> Unit = {},
    private val onCommand: (Int) -> Unit = {},
) : Closeable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("AutoClient-$role").apply { start() }
    private val io = Handler(thread.looper)
    @Volatile private var owner: IBinder = Binder()
    @Volatile private var closed = false
    @Volatile private var api: IAutoService? = null
    private var bound = false
    @Volatile private var generation = 0
    private var retries = 0
    @Volatile private var lastMedia: Bundle? = null
    @Volatile private var lastNavigation: Bundle? = null
    @Volatile private var lastArtwork: ByteArray? = null
    private var borrowed: Surface? = null
    @Volatile private var active = false
    private val media = AtomicReference<Bundle?>()
    private val navigation = AtomicReference<Bundle?>()
    private val art = AtomicReference<ByteArray?>()
    private val flush = Runnable { flushLatest() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val ticket = ++generation
            val lease = Binder().also { owner = it }
            retries = 0
            val next = IAutoService.Stub.asInterface(binder)
            val callback = object : IAutoCallback.Stub() {
                override fun onSnapshot(snapshot: Bundle) { main.post {
                    if (!closed && generation == ticket) {
                        snapshot.classLoader = javaClass.classLoader
                        if (snapshot.containsKey("command")) onCommand(snapshot.getInt("command")) else onSnapshot(snapshot)
                    }
                } }
                override fun onSurface(kind: String, surface: Surface) { main.post {
                    if (closed || generation != ticket) { surface.release(); return@post }
                    val old = borrowed
                    borrowed = surface
                    onSurface(surface)
                    old?.release()
                } }
                override fun onProjectionState(kind: String, state: String) { main.post {
                    if (!closed && generation == ticket) {
                        if (state == "surface_closed") { onSurface(null); borrowed?.release(); borrowed = null }
                        onState(state)
                    }
                } }
            }
            io.post {
                try {
                    check(next.protocolVersion == AutoContract.VERSION) { "protocol_version_mismatch" }
                    if (closed || generation != ticket) return@post
                    api = next
                    next.attach(lease, role, callback)
                    next.setActive(lease, active)
                    media.compareAndSet(null, lastMedia)
                    navigation.compareAndSet(null, lastNavigation)
                    art.compareAndSet(null, lastArtwork)
                    flushLatest()
                } catch (error: Exception) { state("connect:${error.javaClass.simpleName}") }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { lost("service_disconnected") }
        override fun onNullBinding(name: ComponentName) { lost("binding_rejected"); rebind() }
        override fun onBindingDied(name: ComponentName) { lost("binding_died"); rebind() }
    }
    init { require(AutoContract.roleValid(role)) }
    fun connect() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || bound) return
        val intent = Intent(AutoContract.ACTION).setComponent(ComponentName(AutoContract.SERVICE_PACKAGE, AutoContract.SERVICE_CLASS))
        try {
            app.startForegroundService(intent)
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) { state("service_unavailable"); rebind() }
        } catch (error: Exception) { state("bind:${error.javaClass.simpleName}"); rebind() }
    }
    fun setActive(value: Boolean) { active = value; io.post { call { it.setActive(owner, value) } } }
    fun publishMedia(value: Bundle) {
        val next = AutoContract.sanitizeMedia(value)
        if (lastMedia?.getString("track") != next.getString("track")) { lastArtwork = null; art.set(null) }
        lastMedia = next; media.set(next); scheduleFlush()
    }
    fun publishNavigation(value: Bundle) {
        lastNavigation = AutoContract.sanitizeNavigation(value); navigation.set(lastNavigation); scheduleFlush()
    }
    fun publishArtwork(bytes: ByteArray) {
        require(bytes.size <= 2 * 1024 * 1024)
        lastArtwork = bytes.copyOf(); art.set(lastArtwork); scheduleFlush()
    }
    private fun scheduleFlush() { io.removeCallbacks(flush); io.post(flush) }
    private fun flushLatest() {
        val remote = api ?: return
        if (closed) return
        call {
            media.getAndSet(null)?.let { value -> remote.publishMedia(owner, value) }
            navigation.getAndSet(null)?.let { value -> remote.publishNavigation(owner, value) }
            art.getAndSet(null)?.let { bytes ->
                // 普通只读FD没有pipe写满/读端不消费造成的永久等待，临时文件仅在本App缓存中。
                val file = java.io.File.createTempFile("auto-art-", ".bin", app.cacheDir)
                try {
                    file.writeBytes(bytes)
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> remote.publishArtwork(owner, fd) }
                } finally { file.delete() }
            }
        }
    }
    fun executeShell(transport: String, command: String, timeoutMillis: Int = 10000, result: (Bundle) -> Unit) {
        val ticket = generation
        io.post { call { remote -> remote.executeShell(owner, transport, command, timeoutMillis, object : IAutoResult.Stub() {
            override fun onResult(value: Bundle) { main.post { if (!closed && generation == ticket) result(value) } }
        }) } }
    }
    fun vehiclePage(offset: Int, catalog: Boolean = false, result: (Bundle) -> Unit) {
        io.post { call { remote ->
            val page = if (catalog) remote.getVehicleCatalog(offset, 32) else remote.getVehicleProperties(offset, 32)
            main.post { if (!closed) result(page) }
        } }
    }
    fun vehicleValue(propertyId: Int, areaId: Int, offset: Int, result: (Bundle) -> Unit) {
        io.post { call { remote ->
            val value = remote.getVehicleValue(propertyId, areaId, offset)
            main.post { if (!closed) result(value) }
        } }
    }
    fun mediaCommand(command: Int) { io.post { call { it.mediaCommand(owner, command) } } }
    private fun call(block: (IAutoService) -> Unit) {
        val remote = api ?: return
        if (!closed) try { block(remote) } catch (error: Exception) { state("ipc:${error.javaClass.simpleName}") }
    }
    private fun state(value: String) { main.post { if (!closed) onState(value) } }
    private fun lost(reason: String) {
        ++generation; api = null
        onSurface(null); borrowed?.release(); borrowed = null
        onState(reason)
    }
    private val retry = Runnable { connect() }
    private fun rebind() {
        if (bound) { runCatching { app.unbindService(connection) }; bound = false }
        if (!closed && retries < 3) {
            main.removeCallbacks(retry)
            main.postDelayed(retry, 1000L shl retries++)
        }
    }
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true; ++generation
        main.removeCallbacks(retry)
        onSurface(null); borrowed?.release(); borrowed = null
        val previous = api; api = null
        if (bound) { app.unbindService(connection); bound = false }
        io.removeCallbacksAndMessages(null)
        media.set(null); navigation.set(null); art.set(null)
        lastMedia = null; lastNavigation = null; lastArtwork = null
        io.post { runCatching { previous?.detach(owner) }; thread.quitSafely() }
    }
}
