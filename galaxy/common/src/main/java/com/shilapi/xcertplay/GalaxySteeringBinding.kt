package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.steering.GalaxySteeringMediaDispatch
import com.shilapi.xcertplay.steering.GalaxySteeringSessions
import java.io.Closeable

/**
 * 投屏会话 → 方控监听的唯一装配点。
 *
 * 接点：`GalaxyProductHostAdapter.bindMedia/sessionStored` 建立会话时挂接，`detachMedia`
 * 与会话结束（无活动手机会话的 active=false）时解绑。重复挂接、重复解绑、会话重建
 * 都按控制器身份幂等：同一控制器只保留一个句柄，替换控制器时先关旧句柄再挂新句柄。
 * 线程：`GalaxySteeringSessions` 要求主线程持有 attach/observe/close，而宿主回调来自
 * 连接与断开链路，因此这里统一投递到主线程执行。
 * 幂等与迟到回调：每次挂接/解绑都递增 `generation`，排队中的旧任务在代次不匹配时直接丢弃，
 * close 之后到达的回调不会重新建立监听；媒体出口也自行记录 closed，close 后不派发。
 * 释放：`GalaxySteeringSessions` 在最后一个句柄释放后注销Binder订阅并退出worker，本类不额外持有。
 */
internal object GalaxySteeringBinding {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var controller: CarPlayController? = null
    private var handle: Closeable? = null
    private var media: GalaxySteeringMediaDispatch? = null

    /** 会话/媒体建立时挂接；同一控制器重复调用为空操作。 */
    fun attach(context: Context, next: CarPlayController) {
        val app = context.applicationContext
        val ticket = ++generation
        main.post {
            if (ticket != generation) return@post
            if (controller === next && handle != null) return@post
            release()
            val dispatch = GalaxySteeringMediaDispatch(next)
            val attached = runCatching { GalaxySteeringSessions.attach(app, next, dispatch::send) }.getOrNull()
            if (attached == null) {
                // 挂接失败时不保留半挂接状态，下一会话重新尝试；不在这里禁用方控。
                dispatch.close()
                return@post
            }
            controller = next
            media = dispatch
            handle = attached
        }
    }

    /** 媒体/会话解绑；`expected` 为空表示释放当前句柄，非当前控制器的解绑请求被忽略。 */
    fun detach(expected: CarPlayController?) {
        val ticket = ++generation
        main.post {
            if (ticket != generation) return@post
            if (expected != null && controller !== expected) return@post
            release()
        }
    }

    /** 手机会话结束但应用未重启（断线重连前）时释放监听；仍有活动会话的迟到false不动。 */
    fun onSessionEnded(expected: CarPlayController) {
        if (expected.hasActiveSession()) return
        detach(expected)
    }

    private fun release() {
        val closing = handle
        val dispatch = media
        handle = null
        media = null
        controller = null
        closing?.let { runCatching { it.close() } }
        dispatch?.close()
    }
}