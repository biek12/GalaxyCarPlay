package com.ecarx.autoservice.platform

import android.content.Context
import android.os.*
import com.ecarx.autoservice.projection.GalaxyHabKey
import java.io.Closeable

/** 基础连接持续维护，QNX仅按投屏需求准备。两者队列隔离，界面没有连接所有权。 */
internal class PlatformCoordinator(
    private val context: Context,
    private val report: (String) -> Unit,
    private val foundationChanged: (Boolean) -> Unit = {},
) : Closeable {
    private val thread = HandlerThread("AutoService-foundation").apply { start() }
    private val worker = Handler(thread.looper)
    private val projectionThread by lazy { HandlerThread("AutoService-qnx").apply { start() } }
    private val projectionWorkerDelegate = lazy { Handler(projectionThread.looper) }
    private val projectionWorker by projectionWorkerDelegate
    private val main = Handler(Looper.getMainLooper())
    private val access = PlatformAccess(context)
    private val previousClose = closing
    @Volatile private var closed = false
    @Volatile private var foundationReady = false
    @Volatile private var generation = 0
    private var projectionRequested = false
    private var projectionReady = false
    private var projectionPreparing = false
    private var projectionCallback: (Boolean) -> Unit = {}
    private var projectionRetry: Runnable? = null
    private var projectionFailures = 0
    private val recovery = FoundationRecovery({ !closed }, { delay, action ->
        val task = Runnable(action); main.postDelayed(task, delay); AutoCloseable { main.removeCallbacks(task) }
    }, { active, complete ->
        worker.post {
            val result = runCatching {
                previousClose.get(90, java.util.concurrent.TimeUnit.SECONDS)
                check(active()) { "foundation_cancelled" }
                access.ensureRoot()
                check(active()) { "foundation_cancelled" }
                val permissions = AppPermissionSetup(context).repair(active)
                check(active()) { "foundation_cancelled" }
                context.getSharedPreferences("platform", 0).edit().putString("vehicleMode", "root").apply()
                report("platform:root_ready $permissions")
            }
            main.post {
                if (active()) {
                    val changed = foundationReady != result.isSuccess
                    foundationReady = result.isSuccess
                    if (changed) foundationChanged(foundationReady)
                    if (result.isFailure) report("platform:foundation_failed ${stage(result.exceptionOrNull())}")
                    if (foundationReady && projectionRequested && !projectionReady) prepareProjection()
                    if (!foundationReady) { ++generation; projectionPreparing = false; projectionReady = false; projectionCallback(false) }
                }
                complete(result.isSuccess)
            }
        }
    })
    fun start() { recovery.start() }
    fun retry() {
        ++generation; projectionReady = false; projectionPreparing = false; projectionCallback(false)
        access.cancel(); recovery.close(); recovery.start()
        projectionFailures = 0
        if (projectionRequested && foundationReady) prepareProjection()
    }
    fun prepare(active: Boolean, ready: (Boolean) -> Unit) {
        projectionRequested = active; projectionCallback = ready
        if (!active) {
            ++generation; projectionReady = false; projectionPreparing = false
            projectionRetry?.let(main::removeCallbacks); projectionRetry = null
            if (projectionWorkerDelegate.isInitialized()) projectionWorker.post {
                runCatching { RootServiceClient.request(context, 5, QnxCredentials.loadOptional(context), 20000) }
            }
            ready(false)
        } else if (projectionReady) ready(true)
        else if (foundationReady) prepareProjection()
    }
    private fun prepareProjection() {
        if (closed || projectionPreparing || !projectionRequested) return
        projectionRetry?.let(main::removeCallbacks); projectionRetry = null
        projectionPreparing = true
        val ticket = ++generation
        projectionWorker.post {
            val result = runCatching {
                check(!closed && ticket == generation) { "projection_cancelled" }
                val request = QnxCredentials.loadOptional(context)
                val key = GalaxyHabKey.load(context)
                try { request.put("ownerKey", android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)) } finally { key.fill(0) }
                val deployed = RootServiceClient.request(context, 4, request, 50000)
                check(deployed.optString("state") == "receiver_listening") { deployed.optString("stage", "qnx_deploy_failed") }
                report("platform:qnx_receiver_listening reused=${deployed.optBoolean("reused")}")
            }
            main.post {
                if (!closed && ticket == generation) {
                    projectionPreparing = false; projectionReady = result.isSuccess
                    projectionCallback(projectionReady)
                    if (result.isFailure) {
                        report("platform:qnx_failed ${stage(result.exceptionOrNull())}")
                        val delay = (30000L shl projectionFailures.coerceAtMost(3)).coerceAtMost(300000)
                        projectionFailures++
                        projectionRetry = Runnable { if (projectionRequested && foundationReady) prepareProjection() }.also { main.postDelayed(it, delay) }
                    } else projectionFailures = 0
                }
            }
        }
    }
    fun rootLost() {
        if (!foundationReady || closed) return
        foundationReady = false; projectionReady = false
        foundationChanged(false); projectionCallback(false)
        retry()
    }
    override fun close() {
        if (closed) return
        closed = true; ++generation; recovery.close(); access.close(); RootServiceClient.closeVehicle()
        projectionRetry?.let(main::removeCallbacks)
        val completed = java.util.concurrent.CompletableFuture<Unit>().also { closing = it }
        val finish: () -> Unit = {
            try { runCatching { RootServiceClient.stop(context) } }
            finally { completed.complete(Unit); thread.quitSafely() }
        }
        if (projectionWorkerDelegate.isInitialized()) projectionWorker.post {
            runCatching { RootServiceClient.request(context, 5, QnxCredentials.loadOptional(context), 20000) }
            projectionThread.quitSafely(); worker.post(finish)
        } else worker.post(finish)
    }
    private companion object { var closing = java.util.concurrent.CompletableFuture.completedFuture(Unit) }
    private fun stage(error: Throwable?) = error?.message?.takeIf { it.matches(Regex("[a-z_]+")) } ?: error?.javaClass?.simpleName ?: "unknown"
}
