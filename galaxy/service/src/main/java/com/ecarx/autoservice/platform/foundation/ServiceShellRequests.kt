package com.ecarx.autoservice.platform.foundation

import android.os.Bundle
import com.ecarx.autoservice.ipc.ClientRegistry
import com.galaxy.auto.contract.IAutoResult
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shell 排队之后的执行和回执。
 *
 * busy 只在这里。执行仍交给原来的 shell worker 和同一个 [PlatformAccess.executeShell]，
 * 不在 Binder 线程做 IO。先放开 busy，再回到 main 核对同一份租约是否还在。
 */
internal class ServiceShellRequests(
    private val postShell: (Runnable) -> Unit,
    private val postMain: (Runnable) -> Unit,
    private val postCallback: (Runnable) -> Unit,
    private val executeShell: (String, Int, String) -> JSONObject,
    private val closed: () -> Boolean,
    private val paused: () -> Boolean,
    private val automatic: () -> Boolean,
    private val leaseAlive: (ClientRegistry.Lease) -> Boolean,
) {
    private val busy = AtomicBoolean()

    /**
     * 执行一条已经通过入口校验的命令。
     *
     * compareAndSet 失败时只回 shell_busy，不排队。成功后先确认未关闭、未暂停且自动运行仍开着，
     * 再调用 [executeShell]。输出仍含 output、exitCode、timedOut、truncated 和原来的 error。
     * 异常只写入异常类名。旧租约或已经关闭时不再回调。
     */
    fun execute(
        lease: ClientRegistry.Lease,
        command: String,
        timeoutMillis: Int,
        transport: String,
        result: IAutoResult,
    ) {
        if (!busy.compareAndSet(false, true)) {
            postCallback(
                Runnable {
                    runCatching {
                        result.onResult(Bundle().apply { putString("error", "shell_busy") })
                    }
                },
            )
        } else {
            postShell(
                Runnable {
                    val response =
                        runCatching {
                            check(!closed() && !paused() && automatic()) { "platform_stopped" }
                            executeShell(command, timeoutMillis, transport)
                        }
                    val output =
                        Bundle().apply {
                            response.getOrNull()?.let { value ->
                                putString("output", value.optString("output"))
                                putInt("exitCode", value.optInt("exitCode", -1))
                                putBoolean("timedOut", value.optBoolean("timedOut"))
                                putBoolean("truncated", value.optBoolean("truncated"))
                                if (value.has("error")) {
                                    putString("error", value.optString("error"))
                                }
                            }
                            response.exceptionOrNull()?.let { error ->
                                putString("error", error.javaClass.simpleName)
                            }
                        }
                    busy.set(false)
                    postMain(
                        Runnable {
                            if (!closed() && leaseAlive(lease)) {
                                postCallback(Runnable { runCatching { result.onResult(output) } })
                            }
                        },
                    )
                },
            )
        }
    }
}
