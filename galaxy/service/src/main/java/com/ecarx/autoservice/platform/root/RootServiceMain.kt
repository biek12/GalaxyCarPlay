package com.ecarx.autoservice.platform.root

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.os.SystemClock
import com.ecarx.autoservice.ServiceTrace
import com.ecarx.autoservice.platform.adb.AdbKeyAuthorization
import com.ecarx.autoservice.platform.hook.QnxClusterHook
import com.ecarx.autoservice.platform.qnx.QnxReceiverCleanup
import com.ecarx.autoservice.vehicle.EcarxVehicleSource
import com.ecarx.autoservice.vehicle.VehicleCatalog
import com.ecarx.autoservice.vehicle.VehicleRootExchange
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 只服务启动它的App进程。控制、只读车辆和HAB分派独立处理；原厂Hook仅走固定材料与可撤销生命周期入口。 */
object RootServiceMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 7 && Process.myUid() == 0)
        val owner = RootOwner(args[0].toInt(), args[1].toInt(), args[2])
        require(owner.uid >= 10000 && owner.valid() && args[3] == owner.socketName)
        val apk = args[5]
        check(System.getenv("CLASSPATH")!!.substringBefore(':') == apk)
        val certificate =
            args[6]
                .also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        // 取消一轮准备时，已交给app_process的启动不能撤回。新一轮可能同时到达；
        // socket的内核绑定决定唯一owner，未取得监听权的本次进程直接退出，不创建线程或崩溃上报。
        val server =
            try {
                LocalServerSocket(owner.socketName)
            } catch (
                _: java.io.IOException,
            ) {
                certificate.fill(0)
                return
            }
        ServiceTrace.configure(
            java.io.File("/data/user/${owner.uid / 100000}/com.ecarx.autoservice/files/diagnostics/root.log"),
            owner.uid,
        )
        ServiceTrace
            .event("root_helper_started")
        val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            ServiceTrace
                .event("root_uncaught_${thread.name}", error)
            previousCrashHandler?.uncaughtException(thread, error)
        }
        // 与星枢RootServiceMain一致。目标固件的Looper.loop会访问主Looper，
        // 缺少初始化会令任何HandlerThread在订阅成功后崩溃并杀死整个UID0 helper。
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
        val specs = VehicleCatalog.forModel(args[4])
        val source = EcarxVehicleSource()
        val sentinelHolder =
            lazy {
                com.ecarx.autoservice.sentinel
                    .RootSentinelPort()
            }
        val sentinel by sentinelHolder
        val instance =
            java.util.UUID
                .randomUUID()
                .toString()
        val lastRequest = AtomicLong(SystemClock.elapsedRealtime())
        val peers = ConcurrentHashMap.newKeySet<LocalSocket>()
        val pool = ThreadPoolExecutor(0, 6, 30, TimeUnit.SECONDS, SynchronousQueue())
        val watchdog = Executors.newSingleThreadScheduledExecutor()

        fun release() {
            peers.forEach { runCatching { it.close() } }
            // 旧整屏 HAB bridge 已归档，helper 不再持有子进程集合需要终止。
            // helper退出不修改整车状态；用户关闭由已登记的哨兵控制请求明确完成。
            runCatching { server.close() }
        }
        watchdog.scheduleWithFixedDelay({
            val ownerValid = owner.valid()
            if (!ownerValid || (peers.isEmpty() && SystemClock.elapsedRealtime() - lastRequest.get() > 300000)) {
                ServiceTrace.event(
                    if (ownerValid) "root_exit_idle" else "root_exit_owner_invalid",
                )
                release()
                // 星枢实车已确认跨线程close不能可靠唤醒accept，最后只结束本helper。
                Process.killProcess(Process.myPid())
            }
        }, 10, 10, TimeUnit.SECONDS)
        val handlers =
            mapOf<RootOperation, (JSONObject) -> JSONObject>(
                RootOperation.HEALTHY to {
                    JSONObject().put("state", "root_ready").put("uid", Process.myUid())
                },
                RootOperation.READ_VEHICLE to { request ->
                    VehicleRootExchange.read(source, specs, args[4], request)
                },
                RootOperation.SHELL to { request ->
                    ShellExecutor.execute(request.getString("command"), request.getInt("timeoutMillis"))
                },
                RootOperation.RECEIVER_CLEANUP to { request -> QnxReceiverCleanup.stop(request) },
                RootOperation.HOOK_CONTROL to { request ->
                    QnxClusterHook.control(request, owner.uid / 100000)
                },
                RootOperation.HOOK_RECEIPT to { QnxClusterHook.receipt() },
                RootOperation.AUTHORIZE_ADB_KEY to { request ->
                    AdbKeyAuthorization.authorize(
                        request.getString("publicKey"),
                        request.optBoolean("forceReload"),
                    )
                },
                RootOperation.SENTINEL to { request ->
                    sentinel.control(
                        request.getString("action"),
                        request.optLong("powerSequence"),
                        request.optLong("requestAtNanos"),
                    )
                },
                RootOperation.STOP_VEHICLE to { VehicleRootExchange.stop(source) },
            )
        try {
            while (owner.valid()) {
                val peer = server.accept()
                if (!owner.accepts(peer.peerCredentials)) {
                    peer.close()
                    continue
                }
                peers.add(peer)
                try {
                    pool.execute {
                        try {
                            peer.use { client ->
                                client.soTimeout = 5000
                                val input = DataInputStream(client.inputStream)
                                val output = DataOutputStream(client.outputStream)
                                RootServiceAuthentication.verifyClient(input, output, certificate)
                                RootWire.write(
                                    output,
                                    JSONObject()
                                        .put("version", RootWire.VERSION)
                                        .put("uid", Process.myUid())
                                        .put("pid", Process.myPid())
                                        .put("ownerPid", owner.pid)
                                        .put("ownerStarted", owner.started)
                                        .put("apk", apk)
                                        .put("instance", instance),
                                )
                                while (owner.accepts(client.peerCredentials)) {
                                    val operation = input.readInt()
                                    lastRequest.set(SystemClock.elapsedRealtime())
                                    val spec = RootOperation.fromCode(operation)
                                    if (spec == RootOperation.STOP_HELPER) {
                                        ServiceTrace
                                            .event("root_exit_requested")
                                        output.writeInt(0)
                                        output.flush()
                                        release()
                                        Process.killProcess(Process.myPid())
                                        return@execute
                                    }
                                    if (spec == RootOperation.SENTINEL_STREAM) {
                                        sentinel.stream(output)
                                        return@execute
                                    }
                                    // 已退役的 op4/op6 在同一拒绝方法内答复并关闭连接；未退役的 operation 交回表分发。
                                    if (RootProjectionArchive.rejectIfArchived(
                                            operation,
                                            input,
                                            output,
                                        ) { client.close() }
                                    ) {
                                        return@execute
                                    }
                                    val request =
                                        if (spec?.dispatchReadsJson == true) {
                                            RootWire.read(input, 16384)
                                        } else {
                                            JSONObject()
                                        }
                                    val result =
                                        try {
                                            val handler = spec?.let { handlers[it] } ?: error("unknown_operation")
                                            handler(request)
                                        } catch (error: Exception) {
                                            ServiceTrace
                                                .event("root_operation_$operation", error)
                                            JSONObject().put("error", error.javaClass.simpleName).put(
                                                "stage",
                                                error.message?.takeIf { it.matches(Regex("[a-z_]+")) }
                                                    ?: "root_operation_failed",
                                            )
                                        }
                                    RootWire.write(output, result)
                                }
                            }
                        } catch (error: Exception) {
                            if (error !is java.io.EOFException && error !is java.net.SocketTimeoutException) {
                                ServiceTrace
                                    .event("root_peer_failed", error)
                            }
                        } finally {
                            peers.remove(peer)
                            lastRequest.set(SystemClock.elapsedRealtime())
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    peers.remove(peer)
                    peer.close()
                }
            }
        } finally {
            release()
            watchdog.shutdownNow()
            pool.shutdownNow()
            source.close()
            if (sentinelHolder.isInitialized())sentinel.close()
            certificate.fill(0)
        }
    }
}
