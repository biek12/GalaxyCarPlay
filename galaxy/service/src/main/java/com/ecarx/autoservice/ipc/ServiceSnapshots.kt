package com.ecarx.autoservice.ipc

import android.os.Bundle
import com.galaxy.auto.contract.IAutoCallback

/**
 * 跨线程快照缓存、接收者表和回调通知的唯一所有者。
 *
 * [post] 与 [remove] 是调用方已经存在的调度边界，本类不创建线程，也不关闭那个 worker。
 * [isClosed] 在构造时不读取，只在通知真正执行时读取。缓存替换和接收者更新发生在调用
 * [publish] 的线程；回调发生在 [post] 把通知派去的线程。
 */
internal class ServiceSnapshots(
    private val post: (Runnable) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val isClosed: () -> Boolean,
) {
    private val lock = Any()
    private var latest = Bundle()
    private var recipients = emptyList<IAutoCallback>()

    /**
     * 唯一可反复移除再投递的通知。
     *
     * 在 [post] 的线程执行。先读 [isClosed]，服务已关闭就直接返回。
     * 随后分两次取得 [lock]：第一次只做 [latest] 的顶层浅拷贝，第二次只复制接收者列表。
     * 这两次读取不是同一锁块里的原子事务。锁外按列表顺序回调，每位收到自己的
     * `Bundle(state)`，同样只拷顶层。每位单独捕获异常，前一位失败不中断后一位，
     * 也不重试、不记日志、不改错误状态。
     */
    private val notify =
        Runnable {
            if (isClosed()) return@Runnable
            val state = synchronized(lock) { Bundle(latest) }
            val list = synchronized(lock) { recipients.toList() }
            list.forEach { callback -> runCatching { callback.onSnapshot(Bundle(state)) } }
        }

    /**
     * 在 [lock] 内返回当前快照的顶层浅拷贝。
     *
     * 交给调用方的是新 Bundle，不是缓存本体。不递归复制嵌套 Bundle、数组或 Bitmap，
     * 也不附加序列号或新字段。调用方改返回值的顶层字段，不会出现在下一次读取里。
     * Binder 线程可以同步调用。
     */
    fun current(): Bundle = synchronized(lock) { Bundle(latest) }

    /**
     * 在同一个 [lock] 块里换上 [snapshot] 引用和 [callbacks]，再撤掉同一个 [notify]。
     *
     * 直接保存调用方传入的引用，不再深拷贝，不去重，也不按角色筛选。
     * 发布当时不读 [isClosed]。当前接收者非空才把同一个 [notify] 重新 [post]；
     * 空列表会取消上一轮还没执行的通知。接收者列表由调用方在原位置生成。
     */
    fun publish(
        snapshot: Bundle,
        callbacks: List<IAutoCallback>,
    ) {
        synchronized(lock) {
            latest = snapshot
            recipients = callbacks
        }
        remove(notify)
        if (recipients.isNotEmpty()) post(notify)
    }
}
