package com.shilapi.xcertplay.steering

import android.os.SystemClock
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 方控媒体动作的运行时出口：跨源窗口 → 上游蓝牙等待闸门 → 手机媒体命令。
 *
 * 线程：只由方控会话在主线程的派发/心跳中调用；`close` 由会话解绑触发，close 后迟到的
 *      派发直接返回false，不再触碰控制器。
 * 返回值：只有命令真的交给控制器发送才为true。“等蓝牙媒体独占解除”“旧会话”“窗口内重复”
 *      都返回false，让学习页与状态行如实反映本次按键是否已经生效，而不是无条件成功。
 */
internal class GalaxySteeringMediaDispatch(
    private val controller: CarPlayController,
    private val deduplicator: SteeringMediaCommandDeduplicator = GalaxySteeringMediaWindow.deduplicator,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val closed = AtomicBoolean(false)

    fun send(index: Int): Boolean {
        if (closed.get()) return false
        val action = GalaxySteeringMediaWindow.action(index) ?: return false
        // 控制器已关闭或手机会话已经更替时不再发送；owner 在发送时重新读取，不用旧快照。
        if (controller.isClosed()) return false
        val owner = controller.activeMediaSessionOwner() ?: return false
        val now = clock()
        if (!deduplicator.allowed(action, GalaxySteeringMediaWindow.STEERING, now)) return false
        var sent = false
        // 上游既有路径：媒体独占时PLAY要等A2DP断开，旧控制器在这里被丢弃。
        CarPlayBackgroundSession.beforeMediaCommand(controller, index,
            { sent = controller.sendMediaButton(index, owner) }) { }
        if (!sent) return false
        deduplicator.accept(action, GalaxySteeringMediaWindow.STEERING, now)
        return true
    }

    fun close() {
        closed.set(true)
    }
}