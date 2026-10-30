package com.ecarx.autoservice.platform

import android.content.Context
import android.net.LocalSocket
import android.os.Process
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

/** 私有root协议v2。握手仅做兼容认证，权限边界同时依赖内核peer与owner生命期。 */
internal object RootWire {
    const val VERSION = 2
    const val MAX_REPLY = 4 * 1024 * 1024
    fun write(output: DataOutputStream, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_REPLY)
        output.writeInt(bytes.size); output.write(bytes); output.flush()
    }
    fun read(input: DataInputStream, limit: Int = MAX_REPLY): JSONObject {
        val size = input.readInt().also { require(it in 1..limit) }
        val bytes = ByteArray(size); input.readFully(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }
    fun authenticate(context: Context, socket: LocalSocket) {
        check(socket.peerCredentials.uid == 0) { "root_peer_uid_mismatch" }
        RootServiceAuthentication.answerChallenge(DataInputStream(socket.inputStream), DataOutputStream(socket.outputStream),
            RootServiceAuthentication.signingCertificateHash(context))
        val hello = read(DataInputStream(socket.inputStream), 4096)
        check(hello.getInt("version") == VERSION && hello.getInt("uid") == 0 &&
            hello.getInt("pid") == socket.peerCredentials.pid && hello.getInt("ownerPid") == Process.myPid() &&
            hello.getString("ownerStarted") == RootOwner.current().started && hello.getString("apk") == context.applicationInfo.sourceDir &&
            hello.getString("instance").matches(Regex("[0-9a-f-]{36}"))) { "root_hello_mismatch" }
    }
}
