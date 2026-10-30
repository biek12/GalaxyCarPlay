package com.ecarx.autoservice.platform

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

/** 控制连接短用即关；车辆通道复用，避免每轮socket/认证。取消可从其它线程关闭阻塞I/O。 */
internal object RootServiceClient {
    private val vehicleLock = Any()
    @Volatile private var vehicleSocket: LocalSocket? = null
    fun socketName(context: Context) = RootOwner.current().socketName
    fun connect(context: Context, timeout: Int = 5000): LocalSocket {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketName(context))); socket.soTimeout = timeout
            RootWire.authenticate(context, socket)
            return socket
        } catch (error: Exception) { socket.close(); throw error }
    }
    fun healthy(context: Context): Boolean = request(context, 0, JSONObject(), 5000).optString("state") == "root_ready"
    fun read(context: Context): JSONObject = synchronized(vehicleLock) {
        val socket = vehicleSocket ?: connect(context).also { vehicleSocket = it }
        try {
            DataOutputStream(socket.outputStream).apply { writeInt(1); flush() }
            RootWire.read(DataInputStream(socket.inputStream)).also { check(it.has("samples")) }
        } catch (error: Exception) { closeVehicle(); throw error }
    }
    fun request(context: Context, operation: Int, payload: JSONObject, timeout: Int = 35000): JSONObject = connect(context, timeout).use { socket ->
        DataOutputStream(socket.outputStream).apply {
            writeInt(operation)
            if (operation in 3..5 || operation == 7) {
                val bytes = payload.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= 16384)
                writeInt(bytes.size); write(bytes)
            }
            flush()
        }
        RootWire.read(DataInputStream(socket.inputStream))
    }
    fun closeVehicle() { val socket = vehicleSocket; vehicleSocket = null; runCatching { socket?.close() } }
    fun stop(context: Context) {
        closeVehicle()
        connect(context).use { socket ->
            DataOutputStream(socket.outputStream).apply { writeInt(2); flush() }
            check(DataInputStream(socket.inputStream).readInt() == 0)
        }
    }
}
