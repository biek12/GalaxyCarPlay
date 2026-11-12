package com.ecarx.autoservice.platform.root

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.ecarx.autoservice.settings.ServiceFeatures
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * App 侧 Root 套接字端口。
 *
 * 控制连接短用即关；车辆读取复用同一条已认证连接。
 * 调用线程阻塞到超时。取消由 [ControlWork] 从其它线程关闭阻塞 I/O。
 * 失败时关闭本次套接字；车辆读取失败时同时丢掉复用连接。
 */
internal object RootServiceClient {
    private val vehicleLock = Any()

    @Volatile private var vehicleSocket: LocalSocket? = null

    /** 当前进程对应的本机 Root 套接字名。不打开连接。 */
    fun socketName(context: Context) = RootOwner.current().socketName

    /**
     * 打开一条已认证的短连接。调用线程阻塞到超时。
     * 失败时关闭套接字。调用方拥有返回的连接。
     */
    fun connect(context: Context, timeout: Int = 5000): LocalSocket = connectOwned(context, timeout, false)

    /**
     * 健康检查。op0，无 JSON，超时 5000ms。
     * 调用线程阻塞。连接在返回前关闭。
     */
    fun healthy(context: Context): Boolean =
        request(context, RootOperation.HEALTHY, JSONObject(), 5000).optString("state") == "root_ready"

    /**
     * 车辆读取。op1，复用连接，写 int 1 与定长 JSON。
     * 调用线程持有车辆连接锁。失败时关闭复用连接，由调用方决定是否重试。
     */
    fun readVehicle(
        context: Context,
        keys: Collection<String>,
        due: Collection<String>,
        scan: Boolean,
    ): JSONObject = synchronized(vehicleLock) {
        val socket = vehicleSocket ?: connectOwned(context, 5000, false).also { vehicleSocket = it }
        try {
            val payload =
                JSONObject()
                    .put("keys", JSONArray(keys.toList()))
                    .put("due", JSONArray(due.toList()))
                    .put("scan", scan)
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 16384) { "vehicle_request_too_large" }
            DataOutputStream(socket.outputStream).apply {
                writeInt(RootOperation.READ_VEHICLE.code)
                writeInt(bytes.size)
                write(bytes)
                flush()
            }
            RootWire.read(DataInputStream(socket.inputStream)).also {
                check(it.has("samples")) { "vehicle_reply_incomplete" }
            }
        } catch (error: Exception) {
            closeVehicle()
            throw error
        }
    }

    /**
     * 在 Root 进程执行 shell。op3，定长 JSON。
     * 调用线程阻塞到 [timeoutMillis]。[work] 可从其它线程关闭本次连接。
     */
    fun shell(
        context: Context,
        payload: JSONObject,
        timeoutMillis: Int = 35000,
        work: ControlWork? = null,
    ): JSONObject = request(context, RootOperation.SHELL, payload, timeoutMillis, work)

    /**
     * 停止 helper。op2，写 int 2，读回复 int 0。
     * 先关闭车辆复用连接。关闭开关已经落盘，这里仍走认证通道，不受 root 开关门控。
     * 调用线程阻塞最多 5000ms。
     */
    fun stopHelper(context: Context) {
        closeVehicle()
        connectOwned(context, 5000, true).use { socket ->
            DataOutputStream(socket.outputStream).apply {
                writeInt(RootOperation.STOP_HELPER.code)
                flush()
            }
            check(DataInputStream(socket.inputStream).readInt() == 0)
        }
    }

    /**
     * 登记 ADB 公钥。op7，定长 JSON。ADB 开关关闭时拒绝，条件在本端口。
     * 调用线程阻塞到 [timeoutMillis]。连接在返回前关闭。
     */
    fun authorizeAdbKey(
        context: Context,
        payload: JSONObject,
        timeoutMillis: Int,
    ): JSONObject = request(context, RootOperation.AUTHORIZE_ADB_KEY, payload, timeoutMillis)

    /**
     * 停止车辆源。op8，无 JSON。
     * 调用线程阻塞到 [timeoutMillis]。连接在返回前关闭。
     */
    fun stopVehicle(context: Context, timeoutMillis: Int = 5000): JSONObject =
        request(context, RootOperation.STOP_VEHICLE, JSONObject(), timeoutMillis)

    /**
     * 哨兵控制。op9，定长 JSON。action 为 disarm 时走正在关闭的连接，条件在本端口。
     * 调用线程阻塞到 [timeoutMillis]。连接在返回前关闭。
     */
    fun sentinel(
        context: Context,
        payload: JSONObject,
        timeoutMillis: Int = 35000,
    ): JSONObject = request(context, RootOperation.SENTINEL, payload, timeoutMillis)

    /**
     * 在已认证连接上开始哨兵流。op10，只写 operation 整数，回复不是 JSON。
     * 不关闭套接字；调用方拥有连接，并在失败或停止时关闭。
     */
    fun sentinelStream(socket: LocalSocket) {
        DataOutputStream(socket.outputStream).apply {
            writeInt(RootOperation.SENTINEL_STREAM.code)
            flush()
        }
    }

    /** 向已认证的 Root 进程发送 Hook 控制。op11，定长 JSON。 */
    fun hookControl(context: Context, payload: JSONObject, timeoutMillis: Int): JSONObject =
        request(context, RootOperation.HOOK_CONTROL, payload, timeoutMillis)

    /**
     * 读取 Hook 回执。op12，无 JSON。
     * 调用线程阻塞到 [timeoutMillis]。连接在返回前关闭。
     */
    fun hookReceipt(context: Context, timeoutMillis: Int): JSONObject =
        request(context, RootOperation.HOOK_RECEIPT, JSONObject(), timeoutMillis)

    /** 关闭车辆复用连接。可从采集线程或关闭线程调用。 */
    fun closeVehicle() {
        val socket = vehicleSocket
        vehicleSocket = null
        runCatching { socket?.close() }
    }

    private fun connectOwned(context: Context, timeout: Int, stopping: Boolean): LocalSocket {
        check(stopping || ServiceFeatures.read(context).root) { "root_disabled" }
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketName(context)))
            socket.soTimeout = timeout
            RootWire.authenticate(context, socket)
            return socket
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    /**
     * 唯一的通用帧写入。ADB 开关、disarm/stop 走关闭连接、16384 上限都在这里。
     * 调用线程阻塞。返回前关闭连接；[work] 取消时也会关闭。
     */
    private fun request(
        context: Context,
        operation: RootOperation,
        payload: JSONObject,
        timeout: Int = 35000,
        work: ControlWork? = null,
    ): JSONObject {
        check(operation != RootOperation.AUTHORIZE_ADB_KEY || ServiceFeatures.read(context).adb) {
            "adb_disabled"
        }
        val stopping =
            (operation == RootOperation.SENTINEL && payload.optString("action") == "disarm") ||
                (operation == RootOperation.HOOK_CONTROL && payload.optString("action") == "stop")
        val socket = connectOwned(context, timeout, stopping)
        val owned = java.io.Closeable { socket.close() }
        work?.own(owned)
        try {
            return socket.use {
                DataOutputStream(socket.outputStream).apply {
                    writeInt(operation.code)
                    if (operation.requestWritesJson) {
                        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                        require(bytes.size <= 16384)
                        writeInt(bytes.size)
                        write(bytes)
                    }
                    flush()
                }
                RootWire.read(DataInputStream(socket.inputStream))
            }
        } finally {
            work?.release(owned)
        }
    }
}
