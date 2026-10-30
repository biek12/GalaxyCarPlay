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
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 7 && Process.myUid() == 0)
        val owner = RootOwner(args[0].toInt(), args[1].toInt(), args[2])
        require(owner.uid >= 10000 && owner.valid() && args[3] == owner.socketName)
        val apk = args[5]
        check(System.getenv("CLASSPATH")!!.substringBefore(':') == apk)
        val certificate = args[6].also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val specs = VehicleCatalog.forModel(args[4])
        val source = EcarxVehicleSource()
        val instance = java.util.UUID.randomUUID().toString()
        val lastRequest = AtomicLong(SystemClock.elapsedRealtime())
        val peers = ConcurrentHashMap.newKeySet<LocalSocket>()
        val pool = ThreadPoolExecutor(0, 6, 30, TimeUnit.SECONDS, SynchronousQueue())
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val server = LocalServerSocket(owner.socketName)
        fun release() {
            peers.forEach { runCatching { it.close() } }; RootHabSession.close()
            runCatching { server.close() }
        }
        watchdog.scheduleWithFixedDelay({
            if (!owner.valid() || (peers.isEmpty() && SystemClock.elapsedRealtime() - lastRequest.get() > 300000)) {
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
                                output.writeInt(0); output.flush(); release(); Process.killProcess(Process.myPid()); return@execute
                            }
                            if (operation == 6) { RootHabSession.run(client, input, output, apk); return@execute }
                            val request = if (operation in 3..5 || operation == 7) RootWire.read(input, 16384) else JSONObject()
                            val result = try {
                                when (operation) {
                                    0 -> JSONObject().put("state", "root_ready").put("uid", Process.myUid())
                                    1 -> synchronized(source) { source.cycle(specs, SystemClock.elapsedRealtime(), args[4]) }
                                    3 -> ShellExecutor.execute(request.getString("command"), request.getInt("timeoutMillis"))
                                    4 -> QnxReceiverDeployer.deploy(request)
                                    5 -> QnxReceiverDeployer.stop(request)
                                    7 -> AdbKeyAuthorization.authorize(request.getString("publicKey"))
                                    else -> error("unknown_operation")
                                }
                            } catch (error: Exception) {
                                JSONObject().put("error", error.javaClass.simpleName).put("stage", error.message?.takeIf { it.matches(Regex("[a-z_]+")) } ?: "root_operation_failed")
                            }
                            RootWire.write(output, result)
                        }
                    } } catch (_: Exception) { /* EOF/timeout/owner失效均结束连接，不输出命令或认证内容。 */ }
                    finally { peers.remove(peer); lastRequest.set(SystemClock.elapsedRealtime()) }
                } } catch (_: RejectedExecutionException) { peers.remove(peer); peer.close() }
            }
        } finally { release(); watchdog.shutdownNow(); pool.shutdownNow(); source.close(); certificate.fill(0) }
    }
}
