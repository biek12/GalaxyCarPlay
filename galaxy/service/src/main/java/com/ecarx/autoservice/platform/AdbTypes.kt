package com.ecarx.autoservice.platform

import java.io.Closeable
import java.io.IOException

/** 星枢连接层所需的窄类型；不迁入其终端、远程控制或车辆写入域。 */
internal enum class ControlFailure {
    INVALID_REQUEST, SECURE_ADB_REQUIRED, DISCONNECTED, ADB_TLS_UNAVAILABLE,
    PROTOCOL_ERROR, ROOT_REQUIRED, AUTHENTICATION_STORAGE_ERROR, VENDOR_AUTH_REQUIRED,
    AUTHENTICATION_REJECTED, TRANSPORT_ERROR, TIMEOUT
}
internal class ControlTransportException(val reason: ControlFailure) : IOException(reason.name.lowercase())
internal data class ControlIdentity(val uid: Int?)
internal interface RootAuthenticationStore { fun readVpwd(): CharArray? }
internal object RootCredentialRules {
    fun valid(value: CharArray) = value.size in 1..256 && value.none { it == '\n' || it == '\r' || it == '\u0000' }
}
internal data class AdbConnectionSettings(val host: String, val port: Int, val directConnect: Boolean) {
    fun valid() = host in setOf("127.0.0.1", "localhost", "::1") && port in 1..65535
}
internal interface AdbConnectionSettingsStore {
    fun read(): AdbConnectionSettings
    fun save(settings: AdbConnectionSettings): Boolean
}
internal interface AdbSession : Closeable {
    fun connect()
    fun shell(command: String, limit: Int): ShellV2Output
    fun root(): String
}
internal data class ShellV2Output(val stdout: String, val stderr: String, val exitCode: Int?)
internal object AdbObservedIdentity {
    fun read(session: AdbSession): ControlIdentity {
        val result = session.shell("id -u", 128)
        check(result.exitCode == 0) { "adb_identity_failed" }
        val uid = result.stdout.trim().toIntOrNull()?.takeIf { it >= 0 } ?: error("adb_identity_invalid")
        return ControlIdentity(uid)
    }
}
