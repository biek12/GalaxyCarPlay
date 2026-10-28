package com.shilapi.xcertplay.steering

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 迁自星枢AutolinkSteeringTransport：服务坐标、descriptor、事务1注册/2注销及回调Parcel顺序不变。
 * OEM事务只在独占worker执行；本项目不申请claim，所有回调消费位始终为0。
 */
internal class AutolinkSteeringTransport(context: Context) : SteeringTransport {
    private val app = context.applicationContext
    override fun subscribe(event: (SteeringKeyEvent) -> Unit,
                           status: (SteeringConnection, String) -> Unit): Closeable = Session(event, status)

    private inner class Session(private val event: (SteeringKeyEvent) -> Unit,
                                private val status: (SteeringConnection, String) -> Unit) : Closeable {
        private val closed = AtomicBoolean(false)
        private val queuedEvents = AtomicInteger()
        private val overflowPending = AtomicBoolean(false)
        private val thread = HandlerThread("galaxy-autolink-keys").apply { start() }
        private val worker = Handler(thread.looper)
        private var bound = false
        private var remote: IBinder? = null
        @Volatile private var registered = false
        @Volatile private var epoch = 0L
        private var attempts = 0
        private var death: IBinder.DeathRecipient? = null
        private var callback: IBinder? = null
        private var connection: ServiceConnection? = null
        private val timeout = Runnable { if (!closed.get() && !registered) lost("等待方控注册超时") }

        init { worker.post { bind() } }

        private fun makeCallback(ticket: Long) = object : Binder() {
            init { attachInterface(null, CALLBACK) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
                if (code !in 1..3) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CALLBACK)
                val key = data.readInt()
                val time = SystemClock.elapsedRealtime()
                if (!closed.get() && key > 0) queue(ticket, SteeringKeyEvent(key, when (code) {
                    1 -> SteeringEdge.PRESS
                    2 -> SteeringEdge.RELEASE
                    else -> SteeringEdge.CANCEL
                }, time))
                reply?.writeNoException()
                reply?.writeInt(0)
                return true
            }
        }

        private fun queue(ticket: Long, value: SteeringKeyEvent) {
            if (queuedEvents.incrementAndGet() > MAX_QUEUED_EVENTS) {
                queuedEvents.decrementAndGet()
                // 不把一段被截断的按键序列交给学习器；丢失边沿后按已有有限重连流程清代次。
                if (overflowPending.compareAndSet(false, true)) worker.post {
                    overflowPending.set(false)
                    if (!closed.get() && ticket == epoch) lost("方控回调队列已达上限")
                }
                return
            }
            if (!worker.post {
                queuedEvents.decrementAndGet()
                if (!closed.get() && ticket == epoch && registered) event(value)
            }) queuedEvents.decrementAndGet()
        }

        private fun makeConnection(ticket: Long) = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { worker.post {
                if (closed.get() || ticket != epoch) return@post
                runCatching {
                    check(service.interfaceDescriptor == SERVICE)
                    remote = service
                    val recipient = IBinder.DeathRecipient { worker.post {
                        if (!closed.get() && ticket == epoch) lost("方控Binder已断开")
                    } }
                    death = recipient
                    service.linkToDeath(recipient, 0)
                    transact(service, 1)
                    registered = true
                    attempts = 0
                    worker.removeCallbacks(timeout)
                    status(SteeringConnection.REGISTERED, "注册已接受，等待真实按键回调")
                }.onFailure { lost("方控注册失败：${it.javaClass.simpleName}") }
            } }
            override fun onServiceDisconnected(name: ComponentName) { worker.post {
                if (!closed.get() && ticket == epoch) lost("方控服务已断开")
            } }
            override fun onNullBinding(name: ComponentName) { worker.post {
                if (!closed.get() && ticket == epoch) lost("方控服务返回空绑定")
            } }
            override fun onBindingDied(name: ComponentName) { worker.post {
                if (!closed.get() && ticket == epoch) lost("方控绑定失效")
            } }
        }

        private fun bind() {
            if (closed.get()) return
            val ticket = ++epoch
            callback = makeCallback(ticket)
            val observer = makeConnection(ticket)
            connection = observer
            status(SteeringConnection.CONNECTING, "正在连接Autolink方控服务")
            runCatching {
                val intent = Intent(ACTION).setPackage(PACKAGE)
                val info = app.packageManager.resolveService(intent, 0)?.serviceInfo ?: error("SERVICE_UNAVAILABLE")
                val component = ComponentName(info.packageName, info.name)
                check(component.packageName == PACKAGE)
                bound = app.bindService(intent.setComponent(component), observer, Context.BIND_AUTO_CREATE)
                check(bound)
                worker.postDelayed(timeout, 5_000)
            }.onFailure { lost("方控绑定未接受：${it.javaClass.simpleName}") }
        }

        private fun transact(service: IBinder, code: Int) {
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                request.writeInterfaceToken(SERVICE)
                request.writeStrongBinder(callback)
                check(service.transact(code, request, response, 0))
                response.readException()
            } finally { request.recycle(); response.recycle() }
        }

        private fun release() {
            worker.removeCallbacks(timeout)
            epoch++
            remote?.let { service ->
                runCatching { transact(service, 2) }
                death?.let { recipient -> runCatching { service.unlinkToDeath(recipient, 0) } }
            }
            remote = null
            registered = false
            if (bound) connection?.let { runCatching { app.unbindService(it) } }
            bound = false
            callback = null
            connection = null
            death = null
        }

        private fun lost(reason: String) {
            if (closed.get()) return
            release()
            if (++attempts <= 2) {
                status(SteeringConnection.CONNECTING, reason)
                worker.postDelayed({ bind() }, 2_000)
            } else status(SteeringConnection.FAILED, reason)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            worker.removeCallbacksAndMessages(null)
            worker.post { release(); thread.quitSafely() }
        }
    }

    companion object {
        internal const val PACKAGE = "com.autolink.hardkeyservice"
        internal const val ACTION = "com.autolink.hardkeyservice.HardKeyAdapterService"
        internal const val SERVICE = "com.autolink.adapterbinder.IHardKeyService"
        internal const val CALLBACK = "com.autolink.adapterbinder.IHardKeyCallback"
        private const val MAX_QUEUED_EVENTS = 128
    }
}
