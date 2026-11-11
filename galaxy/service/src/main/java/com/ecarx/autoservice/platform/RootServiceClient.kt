package com.ecarx.autoservice.platform

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import com.ecarx.autoservice.settings.ServiceFeatures

/** 控制连接短用即关；车辆通道复用，避免每轮socket/认证。取消可从其它线程关闭阻塞I/O。 */
internal object RootServiceClient {
    private val vehicleLock = Any()
    @Volatile private var vehicleSocket: LocalSocket? = null
    fun socketName(context: Context) = RootOwner.current().socketName
    fun connect(context: Context, timeout: Int = 5000): LocalSocket = connectOwned(context, timeout, false)
    private fun connectOwned(context: Context, timeout: Int, stopping: Boolean): LocalSocket {
        check(stopping || ServiceFeatures.read(context).root) { "root_disabled" }
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketName(context))); socket.soTimeout = timeout
            RootWire.authenticate(context, socket)
            return socket
        } catch (error: Exception) { socket.close(); throw error }
    }
    fun healthy(context: Context): Boolean = request(context, 0, JSONObject(), 5000).optString("state") == "root_ready"
    /**
     * v4：车辆读取请求带订阅键与到期键，helper只读到期键；scan=true时改走全目录诊断扫描。
     * 请求体走与op3..5相同的定长JSON帧，避免helper与App两侧出现两套帧格式。
     */
    fun read(context: Context, keys: Collection<String>, due: Collection<String>, scan: Boolean): JSONObject = synchronized(vehicleLock) {
        val socket = vehicleSocket ?: connect(context).also { vehicleSocket = it }
        try {
            val payload = JSONObject().put("keys", org.json.JSONArray(keys.toList()))
                .put("due", org.json.JSONArray(due.toList())).put("scan", scan)
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 16384) { "vehicle_request_too_large" }
            DataOutputStream(socket.outputStream).apply { writeInt(1); writeInt(bytes.size); write(bytes); flush() }
            RootWire.read(DataInputStream(socket.inputStream)).also {
                check(it.has("samples")) { "vehicle_reply_incomplete" }
            }
        } catch (error: Exception) { closeVehicle(); throw error }
    }
    fun request(context: Context, operation: Int, payload: JSONObject, timeout: Int = 35000, work: ControlWork? = null): JSONObject {
        check(operation != 7 || ServiceFeatures.read(context).adb) { "adb_disabled" }
        val socket = connectOwned(context, timeout, (operation==9&&payload.optString("action")=="disarm") || (operation==11&&payload.optString("action")=="stop"))
        val owned = java.io.Closeable { socket.close() }
        work?.own(owned)
        try { return socket.use {
            DataOutputStream(socket.outputStream).apply {
                writeInt(operation)
                if (operation in 3..5 || operation == 7 || operation == 9 || operation == 11) {
                    val bytes = payload.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= 16384)
                    writeInt(bytes.size); write(bytes)
                }
                flush()
            }
            RootWire.read(DataInputStream(socket.inputStream))
        } } finally { work?.release(owned) }
    }
    fun closeVehicle() { val socket = vehicleSocket; vehicleSocket = null; runCatching { socket?.close() } }
    fun stop(context: Context) {
        closeVehicle()
        // 关闭开关已经落盘，释放旧helper仍须通过原认证通道，不能被业务入口的开关门控挡住。
        connectOwned(context, 5000, true).use { socket ->
            DataOutputStream(socket.outputStream).apply { writeInt(2); flush() }
            check(DataInputStream(socket.inputStream).readInt() == 0)
        }
    }
}
