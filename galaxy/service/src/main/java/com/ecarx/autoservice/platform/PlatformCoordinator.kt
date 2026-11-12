package com.ecarx.autoservice.platform

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.ecarx.autoservice.settings.ServiceFeatures
import java.io.Closeable

/**
 * 基础 ADB/Root 身份与权限的持续维护者。
 *
 * 旧整屏覆盖投屏已归档：本类不再持有 QNX 投屏线程、不部署接收端、不发 op4、
 * 不做失败重试补拉起，也不在 foundation 完成后重入旧输出准备。车辆订阅、媒体、
 * 原厂元素 Hook 仍由各自 owner 使用这里恢复出的基础身份，不受本次收口影响。
 *
 * 线程：基础恢复在自有 `AutoService-foundation` HandlerThread 上排队；ready 回调
 * 与换代计数在主线程结算。关闭屏障 [previousClose] 保证新一轮恢复不会抢在上一轮
 * 释放之前启动。
 */
internal class PlatformCoordinator(
    private val context: Context,
    private val report: (String) -> Unit,
    private val foundationChanged: (Boolean) -> Unit = {},
    private val features: ServiceFeatures = ServiceFeatures.read(context),
) : Closeable {
    private val thread = HandlerThread("AutoService-foundation").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val access = PlatformAccess(context)
    private val previousClose = closing

    @Volatile private var closed = false

    @Volatile private var foundationReady = false

    @Volatile private var generation = 0
    private val recovery =
        FoundationRecovery({ !closed && (features.adb || features.root) }, { delay, action ->
            val task = Runnable(action)
            main.postDelayed(task, delay)
            AutoCloseable { main.removeCallbacks(task) }
        }, { active, complete ->
            worker.post {
                val result =
                    runCatching {
                        previousClose.get(90, java.util.concurrent.TimeUnit.SECONDS)
                        check(active()) { "foundation_cancelled" }
                        if (features.root) {
                            access.ensureRoot()
                            check(active()) { "foundation_cancelled" }
                            val permissions = AppPermissionSetup(context).repair(active)
                            check(active()) { "foundation_cancelled" }
                            report("platform:root_ready $permissions")
                        } else {
                            access.ensureAdb()
                            report("platform:adb_ready")
                        }
                    }
                main.post {
                    if (active()) {
                        val next = result.isSuccess && features.root
                        val changed = foundationReady != next
                        foundationReady = next
                        if (changed) foundationChanged(foundationReady)
                        if (result.isFailure) report("platform:foundation_failed ${stage(result.exceptionOrNull())}")
                        // 基础恢复失败时只换代作废本轮工作，不再重入任何旧投屏准备。
                        if (!foundationReady) ++generation
                    }
                    complete(result.isSuccess)
                }
            }
        })

    /** 启动基础恢复调度；不再附带旧接收端部署或 QNX 登录。 */
    fun start() {
        recovery.start()
    }

    /** 基础身份的脱敏诊断证据，供服务快照与 dumpsys 使用。 */
    fun foundationEvidence() = access.foundationEvidence

    /** 显式重试新一轮基础恢复；换代使在途回调失效，不再补拉起旧投屏。 */
    fun retry() {
        ++generation
        access.cancel()
        recovery.close()
        recovery.start()
    }

    /** 基础 Root 丢失时作废就绪态并重启恢复；不触碰任何显示输出所有权。 */
    fun rootLost() {
        if (!foundationReady || closed) return
        foundationReady = false
        foundationChanged(false)
        retry()
    }

    override fun close() = close(releaseRoot = true)

    /**
     * 关闭基础恢复并发布关闭屏障；释放 Root 时按已核验的认证通道停自有 helper。
     * 不清理车端接收端、不改原车启动项。
     */
    fun close(releaseRoot: Boolean) {
        if (closed) return
        closed = true
        ++generation
        recovery.close()
        access.close()
        RootServiceClient.closeVehicle()
        val completed =
            java.util.concurrent
                .CompletableFuture<Unit>()
                .also { closing = it }
        val finish: () -> Unit = {
            try {
                if (releaseRoot) runCatching { RootServiceClient.stop(context) }
            } finally {
                completed.complete(Unit)
                thread.quitSafely()
            }
        }
        worker.post(finish)
    }

    private companion object {
        var closing =
            java.util.concurrent.CompletableFuture
                .completedFuture(Unit)
    }

    private fun stage(error: Throwable?) =
        error?.message?.takeIf { it.matches(Regex("[a-z_]+")) } ?: error?.javaClass?.simpleName ?: "unknown"
}
