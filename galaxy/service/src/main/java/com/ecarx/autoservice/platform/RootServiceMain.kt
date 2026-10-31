package com.ecarx.autoservice.platform

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.os.SystemClock
import com.ecarx.autoservice.vehicle.EcarxVehicleSource
import com.ecarx.autoservice.vehicle.VehicleCatalog
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/** 只服务启动它的App进程。控制、只读车辆和HAB分派独立处理；不改变原车进程或设备权限。 */
object RootServiceMain {
    private fun org.json.JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        val out = HashSet<String>(length())
        for (index in 0 until length()) optString(index).takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 7 && Process.myUid() == 0)
        val owner = RootOwner(args[0].toInt(), args[1].toInt(), args[2])
        require(owner.uid >= 10000 && owner.valid() && args[3] == owner.socketName)
        val apk = args[5]
        check(System.getenv("CLASSPATH")!!.substringBefore(':') == apk)
        val certificate = args[6].also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // 取消一轮准备时，已交给app_process的启动不能撤回。新一轮可能同时到达；
        // socket的内核绑定决定唯一owner，未取得监听权的本次进程直接退出，不创建线程或崩溃上报。
        val server = try { LocalServerSocket(owner.socketName) }
        catch (_: java.io.IOException) { certificate.fill(0); return }
        com.ecarx.autoservice.ServiceTrace.configure(java.io.File("/data/user/${owner.uid / 100000}/com.ecarx.autoservice/files/diagnostics/root.log"), owner.uid)
        com.ecarx.autoservice.ServiceTrace.event("root_helper_started")
        val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            com.ecarx.autoservice.ServiceTrace.event("root_uncaught_${thread.name}", error)
            previousCrashHandler?.uncaughtException(thread, error)
        }
        // 与星枢RootServiceMain一致。目标固件的Looper.loop会访问主Looper，
        // 缺少初始化会令任何HandlerThread在订阅成功后崩溃并杀死整个UID0 helper。
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
        val specs = VehicleCatalog.forModel(args[4])
        val source = EcarxVehicleSource()
        val instance = java.util.UUID.randomUUID().toString()
        val lastRequest = AtomicLong(SystemClock.elapsedRealtime())
        val peers = ConcurrentHashMap.newKeySet<LocalSocket>()
        val pool = ThreadPoolExecutor(0, 6, 30, TimeUnit.SECONDS, SynchronousQueue())
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        fun release() {
            peers.forEach { runCatching { it.close() } }; RootHabSession.close()
            runCatching { server.close() }
        }
        watchdog.scheduleWithFixedDelay({
            val ownerValid = owner.valid()
            if (!ownerValid || (peers.isEmpty() && SystemClock.elapsedRealtime() - lastRequest.get() > 300000)) {
                com.ecarx.autoservice.ServiceTrace.event(if (ownerValid) "root_exit_idle" else "root_exit_owner_invalid")
                release()
                // 星枢实车已确认跨线程close不能可靠唤醒accept，最后只结束本helper。
                Process.killProcess(Process.myPid())
            }
        }, 10, 10, TimeUnit.SECONDS)
        try {
            while (owner.valid()) {
                val peer = server.accept()
                if (!owner.accepts(peer.peerCredentials)) { peer.close(); continue }
                peers.add(peer)
                try { pool.execute {
                    try { peer.use { client ->
                        client.soTimeout = 5000
                        val input = DataInputStream(client.inputStream)
                        val output = DataOutputStream(client.outputStream)
                        RootServiceAuthentication.verifyClient(input, output, certificate)
                        RootWire.write(output, JSONObject().put("version", RootWire.VERSION).put("uid", Process.myUid())
                            .put("pid", Process.myPid()).put("ownerPid", owner.pid).put("ownerStarted", owner.started)
                            .put("apk", apk).put("instance", instance))
                        while (owner.accepts(client.peerCredentials)) {
                            val operation = input.readInt()
                            lastRequest.set(SystemClock.elapsedRealtime())
                            if (operation == 2) {
                                com.ecarx.autoservice.ServiceTrace.event("root_exit_requested")
                                output.writeInt(0); output.flush(); release(); Process.killProcess(Process.myPid()); return@execute
                            }
                            if (operation == 6) { RootHabSession.run(client, input, output, apk); return@execute }
                            val request = if (operation == 1 || operation in 3..5 || operation == 7) RootWire.read(input, 16384) else JSONObject()
                            val result = try {
                                when (operation) {
                                    0 -> JSONObject().put("state", "root_ready").put("uid", Process.myUid())
                                    1 -> synchronized(source) {
                                        // 订阅键决定注册与读取；scan=true时额外推进一轮全目录诊断扫描（结果并入同一回复）。
                                        val now = SystemClock.elapsedRealtime()
                                        val keys = request.optJSONArray("keys").toStringSet()
                                        val due = request.optJSONArray("due").toStringSet().intersect(keys)
                                        val reply = source.cycle(specs, now, args[4], keys, due)
                                        if (request.optBoolean("scan")) {
                                            val scan = source.scanCycle(specs, now, args[4])
                                            reply.put("scan", scan.optJSONArray("scan")?.length() ?: 0)
                                            reply.put("scanCursor", scan.optInt("cursor"))
                                        }
                                        reply
                                    }
                                    3 -> ShellExecutor.execute(request.getString("command"), request.getInt("timeoutMillis"))
                                    4 -> QnxReceiverDeployer.deploy(request)
                                    5 -> QnxReceiverDeployer.stop(request)
                                    7 -> AdbKeyAuthorization.authorize(request.getString("publicKey"), request.optBoolean("forceReload"))
                                    8 -> synchronized(source) { source.close(); JSONObject().put("state", "vehicle_stopped") }
                                    else -> error("unknown_operation")
                                }
                            } catch (error: Exception) {
                                com.ecarx.autoservice.ServiceTrace.event("root_operation_$operation", error)
                                JSONObject().put("error", error.javaClass.simpleName).put("stage", error.message?.takeIf { it.matches(Regex("[a-z_]+")) } ?: "root_operation_failed")
                            }
                            RootWire.write(output, result)
                        }
                    } } catch (error: Exception) {
                        if (error !is java.io.EOFException && error !is java.net.SocketTimeoutException)
                            com.ecarx.autoservice.ServiceTrace.event("root_peer_failed", error)
                    }
                    finally { peers.remove(peer); lastRequest.set(SystemClock.elapsedRealtime()) }
                } } catch (_: RejectedExecutionException) { peers.remove(peer); peer.close() }
            }
        } finally { release(); watchdog.shutdownNow(); pool.shutdownNow(); source.close(); certificate.fill(0) }
    }
}
