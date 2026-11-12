package com.ecarx.autoservice.ipc

import android.os.Handler

/**
 * 把 Binder 请求投到服务既有的 main [Handler]。
 *
 * 不新建线程，不复制一份 closed，也不另做队列。
 * 来源字符串由调用方在投递前捕获，任务执行时原样交给失败回调。
 */
internal class ServiceRequestQueue(
    private val main: Handler,
    private val isClosed: () -> Boolean,
    private val onFailure: (String, Throwable) -> Unit,
) {
    /**
     * 把 [block] 排到 main，调用当时不执行。
     *
     * 投递前不读 [isClosed]。任务真正运行时服务已关闭，就跳过 [block] 且不报错。
     * 否则执行 [block]；失败时把入队参数 [source] 和同一个异常交给 [onFailure]，不再包第二层。
     */
    fun enqueue(
        source: String,
        block: () -> Unit,
    ) {
        main.post {
            if (!isClosed()) {
                runCatching(block).onFailure { error -> onFailure(source, error) }
            }
        }
    }
}
