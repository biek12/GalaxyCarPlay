package com.shilapi.xcertplay.steering

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 学习页只持有观察句柄；close后不能再保存或收到迟到回调。 */
interface SteeringLearningSession : Closeable {
    fun snapshot(): SteeringSnapshot
    fun startLearning(): Boolean
    fun cancelLearning()
    fun confirm(gesture: SteeringGesture, action: SteeringAction)
    fun remove(binding: SteeringBinding)
    fun setEnabled(enabled: Boolean)
}

/** 一份OEM注册供投屏与前台学习页共享；最后一个句柄释放后注销Binder并退出worker。 */
object GalaxySteeringSessions {
    private var runtime: Runtime? = null
    @Synchronized private fun runtime(context: Context): Runtime = runtime ?: Runtime(context.applicationContext)
        .also { runtime = it }

    fun attach(context: Context, controller: CarPlayController,
               mediaCommand: ((Int) -> Boolean)? = null): Closeable = runtime(context).attach(controller, mediaCommand)
    fun observe(context: Context, listener: (SteeringSnapshot) -> Unit): SteeringLearningSession =
        runtime(context).observe(listener)

    private class Runtime(private val app: Context) {
        private data class Target(val controller: CarPlayController, val mediaCommand: ((Int) -> Boolean)?)
        private val main = Handler(Looper.getMainLooper())
        private val store = SteeringConfigurationStore(app)
        private val transport: SteeringTransport = AutolinkSteeringTransport(app)
        private var nextId = 0L
        private val targets = linkedMapOf<Long, Target>()
        private val observers = linkedMapOf<Long, (SteeringSnapshot) -> Unit>()
        private var connection: Closeable? = null
        private var generation = 0L
        private val pendingDeliveries = AtomicInteger()
        private val overflowPending = AtomicBoolean(false)
        private var targetOwner: Any? = null
        private var targetController: CarPlayController? = null
        private var configurationError = false
        private val initial = runCatching { store.load() }.getOrElse {
            // 不覆盖损坏/未知版本映射，提示失败；用户显式确认后才可保存新映射。
            configurationError = true
            SteeringConfiguration()
        }
        private val engine = SteeringLearningEngine(initial, SystemClock::elapsedRealtime, ::dispatch)
        private val ticker = object : Runnable {
            override fun run() {
                if (connection == null) return
                refreshOwner()
                val before = engine.snapshot
                engine.tick()
                if (before != engine.snapshot) publish()
                main.postDelayed(this, 50)
            }
        }

        init {
            engine.setEnabled(store.enabled())
            if (configurationError) engine.connection(SteeringConnection.FAILED, "原映射不可读取，未覆盖")
        }

        private fun checkMain() = check(Looper.myLooper() == main.looper) { "方控会话必须由主线程持有" }

        fun attach(controller: CarPlayController, mediaCommand: ((Int) -> Boolean)?): Closeable {
            checkMain()
            val id = ++nextId
            targets[id] = Target(controller, mediaCommand)
            reconcile()
            var closed = false
            return Closeable {
                checkMain()
                if (!closed) { closed = true; targets.remove(id); refreshOwner(); reconcile() }
            }
        }

        fun observe(listener: (SteeringSnapshot) -> Unit): SteeringLearningSession {
            checkMain()
            val id = ++nextId
            observers[id] = listener
            reconcile()
            listener(engine.snapshot)
            return object : SteeringLearningSession {
                private var closed = false
                private fun requireOpen() { checkMain(); check(!closed) }
                override fun snapshot(): SteeringSnapshot { requireOpen(); return engine.snapshot }
                override fun startLearning(): Boolean {
                    requireOpen()
                    return engine.learn().also { publish() }
                }
                override fun cancelLearning() { requireOpen(); engine.cancelLearning(); publish() }
                override fun confirm(gesture: SteeringGesture, action: SteeringAction) {
                    requireOpen()
                    val next = engine.proposed(gesture, action)
                    store.save(next)
                    configurationError = false
                    engine.configure(next)
                    publish()
                }
                override fun remove(binding: SteeringBinding) {
                    requireOpen()
                    val next = engine.snapshot.configuration.copy(bindings = engine.snapshot.configuration.bindings - binding)
                    store.save(next)
                    engine.configure(next)
                    publish()
                }
                override fun setEnabled(enabled: Boolean) {
                    requireOpen()
                    store.setEnabled(enabled)
                    engine.setEnabled(enabled)
                    reconcile()
                    publish()
                }
                override fun close() {
                    checkMain()
                    if (closed) return
                    closed = true
                    observers.remove(id)
                    if (engine.snapshot.learning) engine.cancelLearning()
                    reconcile()
                }
            }
        }

        private fun reconcile() {
            val needed = (targets.isNotEmpty() || observers.isNotEmpty()) && engine.snapshot.enabled
            if (!needed) {
                generation++
                main.removeCallbacks(ticker)
                connection?.close()
                connection = null
                engine.connection(SteeringConnection.STOPPED, "方控监听已关闭")
                targetOwner = null
                targetController = null
                publish()
            } else if (connection == null) {
                val ticket = ++generation
                engine.connection(SteeringConnection.CONNECTING, "正在连接Autolink方控服务")
                connection = transport.subscribe({ event ->
                    deliver(ticket) {
                        refreshOwner()
                        engine.receive(event)
                        publish()
                    }
                }, { status, detail ->
                    deliver(ticket) {
                        engine.connection(status, if (configurationError) "$detail；原映射不可读取，未覆盖" else detail)
                        publish()
                    }
                })
                main.post(ticker)
                publish()
            }
        }

        private fun deliver(ticket: Long, action: () -> Unit) {
            if (pendingDeliveries.incrementAndGet() > 128) {
                pendingDeliveries.decrementAndGet()
                if (overflowPending.compareAndSet(false, true)) main.post {
                    overflowPending.set(false)
                    if (ticket == generation && connection != null) {
                        generation++
                        main.removeCallbacks(ticker)
                        connection?.close()
                        connection = null
                        engine.connection(SteeringConnection.FAILED, "方控事件队列已达上限，请重新开启监听")
                        publish()
                    }
                }
                return
            }
            if (!main.post {
                pendingDeliveries.decrementAndGet()
                if (ticket == generation && connection != null) action()
            }) pendingDeliveries.decrementAndGet()
        }

        private fun refreshOwner() {
            val target = targets.values.lastOrNull()
            val owner = target?.controller?.activeMediaSessionOwner()
            if (targetController !== target?.controller || targetOwner !== owner) {
                targetController = target?.controller
                targetOwner = owner
                engine.sessionChanged()
            }
        }

        private fun dispatch(action: SteeringAction): Boolean {
            val target = targets.values.lastOrNull() ?: return false
            val controller = target.controller
            val owner = targetOwner ?: return false
            if (controller !== targetController || controller.activeMediaSessionOwner() !== owner || controller.isClosed()) return false
            val mediaIndex = when (action) {
                SteeringAction.PLAY -> CarPlayMediaButton.PLAY
                SteeringAction.PAUSE -> CarPlayMediaButton.PAUSE
                SteeringAction.PREVIOUS -> CarPlayMediaButton.PREVIOUS
                SteeringAction.NEXT -> CarPlayMediaButton.NEXT
                else -> null
            }
            if (mediaIndex != null) return target.mediaCommand?.invoke(mediaIndex) ?: controller.sendMediaButton(mediaIndex, owner)
            return when (action) {
                SteeringAction.SIRI -> controller.requestSiri()
                SteeringAction.ANSWER_CALL -> controller.answerCall()
                SteeringAction.END_CALL -> controller.endCall()
                else -> false
            }
        }

        private fun publish() {
            val snapshot = engine.snapshot
            observers.values.toList().forEach { listener -> runCatching { listener(snapshot) } }
        }
    }
}
