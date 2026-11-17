package com.galaxy.auto.client.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.galaxy.auto.client.diagnostics.DiagnosticEvents
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoCallback
import com.galaxy.auto.contract.IAutoService

/**
 * 客户端连接会话：Binder租约、连接代次、绑定与恢复的唯一所有者。
 *
 * 主线程负责connect/绑定/重试排队，io线程负责握手与远端调用。其余owner只通过
 * [isClosed]、[currentGeneration]、[currentOwner]、[currentApi]读取状态，不写连接字段；
 * 也不在此另开worker。[attach]只登记明确函数，构造与装配期间不触发回调、不发RPC。
 */
class ClientConnectionSession(
    private val app: Context,
    private val role: String,
    private val main: Handler,
    private val io: Handler,
    private val onState: (String) -> Unit,
    private val onReady: () -> Unit,
) {
    @Volatile
    private var owner: IBinder = Binder()

    @Volatile
    private var closed = false

    @Volatile
    private var api: IAutoService? = null
    private val connectionLock = Any()
    private var bound = false

    @Volatile
    private var generation = 0
    private var retries = 0

    private lateinit var callbackFactory: (Int) -> IAutoCallback
    private lateinit var onConnected: (IAutoService, IBinder) -> Unit
    private lateinit var onLost: () -> Unit

    /** 供其余owner按原时点捕获代次；不提供写入口，避免复制第二份epoch。 */
    val currentGeneration: Int get() = generation

    /** io/main上的只读租约；调用时读取，不提前快照。 */
    val currentOwner: IBinder get() = owner

    /** 调用时读取的远端接口；为空时常规call静默返回。 */
    val currentApi: IAutoService? get() = api

    /** 终态守卫；close与lost共享同一字段。 */
    val isClosed: Boolean get() = closed

    /** 只登记具体方法引用，不接收可任意读写的Host，也不立即执行。 */
    fun attach(
        callbacks: (Int) -> IAutoCallback,
        replay: (IAutoService, IBinder) -> Unit,
        lost: () -> Unit,
    ) {
        callbackFactory = callbacks
        onConnected = replay
        onLost = lost
    }

    /** 主线程同步入口：仍使用固定组件与BIND_AUTO_CREATE，失败按原诊断与恢复分支处理。 */
    fun connect() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || bound) return
        val intent =
            Intent(AutoContract.ACTION).setComponent(
                ComponentName(AutoContract.SERVICE_PACKAGE, AutoContract.SERVICE_CLASS),
            )
        try {
            app.startForegroundService(intent)
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                postState("service_unavailable")
                rebind()
            }
        } catch (error: Exception) {
            DiagnosticEvents.error(
                "binder_bind",
                error,
                mapOf("caller" to app.packageName, "method" to "bindService"),
            )
            postState("bind:${error.javaClass.simpleName}")
            rebind()
        }
    }

    /**
     * 原远端调用策略：api为空静默返回，未关闭才执行；异常记录binder_call并异步回报状态。
     * 有独立try的一次性请求不走这里，以保留各自回执形状。
     */
    fun call(block: (IAutoService) -> Unit) {
        val remote = api ?: return
        if (!closed) {
            try {
                block(remote)
            } catch (error: Exception) {
                DiagnosticEvents.error("binder_call", error, mapOf("caller" to app.packageName))
                postState("ipc:${error.javaClass.simpleName}")
            }
        }
    }

    /** 普通状态仍main.post后判closed；lost的reason与此不同，是同步直接执行。 */
    fun postState(value: String) {
        main.post { if (!closed) onState(value) }
    }

    /** close第一步：锁内判终态、置closed并原子取旧lease，供后续detach使用。 */
    fun beginClose(): Pair<IAutoService?, IBinder>? =
        synchronized(connectionLock) {
            if (closed) {
                null
            } else {
                closed = true
                (api to owner).also { api = null }
            }
        }

    /** close内推进代次，使排队中的旧ticket与旧回调一并失效；沿用原无锁自增时点。 */
    fun advanceGeneration() {
        ++generation
    }

    /** close时移除唯一的retry，避免终态后再排队一次connect。 */
    fun removeRetry() {
        main.removeCallbacks(retry)
    }

    /** close时解绑；与rebind共用bound标记，但不再排重试。 */
    fun unbindForClose() {
        if (bound) {
            app.unbindService(connection)
            bound = false
        }
    }

    /** io上以关闭时捕获的旧对象detach，绝不误用重连后的新owner。 */
    fun detach(remote: IAutoService?, lease: IBinder) {
        runCatching { remote?.detach(lease) }
    }

    private val connection =
        object : ServiceConnection {
            /** 每代只在这里推进ticket并创建本代回调；握手与补发顺序整体交给Publisher。 */
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                if (closed) return
                val ticket = ++generation
                val lease =
                    Binder().also { owner = it }
                retries = 0
                val next = IAutoService.Stub.asInterface(binder)
                val callback = callbackFactory(ticket)
                io.post {
                    try {
                        check(next.protocolVersion == AutoContract.VERSION) { "protocol_version_mismatch" }
                        synchronized(connectionLock) {
                            if (closed || generation != ticket) return@post
                            api = next
                        }
                        next.attach(lease, role, callback)
                        if (closed || generation != ticket) return@post
                        onConnected(next, lease)
                        main.post { if (!closed && generation == ticket) onReady() }
                    } catch (error: Exception) {
                        DiagnosticEvents.error(
                            "binder_connect",
                            error,
                            mapOf("caller" to app.packageName, "method" to "attach"),
                        )
                        postState("connect:${error.javaClass.simpleName}")
                    }
                }
            }

            /** 普通断开只通报，交给Android原恢复机制，不擅自叠加客户端重绑。 */
            override fun onServiceDisconnected(name: ComponentName) {
                lost("service_disconnected")
            }

            override fun onNullBinding(name: ComponentName) {
                lost("binding_rejected")
                rebind()
            }

            override fun onBindingDied(name: ComponentName) {
                lost("binding_died")
                rebind()
            }
        }

    /** 断线：锁内推进代次并清api，随后同步释放借用Surface并直接回报reason。 */
    private fun lost(reason: String) {
        if (closed) return
        synchronized(connectionLock) {
            ++generation
            api = null
        }
        onLost()
        onState(reason)
    }

    private val retry = Runnable { connect() }

    /** 同一retry按1000/2000/4000ms三次为上限；成功连接时retries已归零。 */
    private fun rebind() {
        if (bound) {
            runCatching { app.unbindService(connection) }
            bound = false
        }
        if (!closed && retries < 3) {
            main.removeCallbacks(retry)
            main.postDelayed(retry, 1000L shl retries++)
        }
    }
}
