package com.ecarx.autoservice.platform.adb

/**
 * 本地 ADB 传输失败原因。
 *
 * 名称的小写形式是 [ControlTransportException] 的消息。调用方在连接线程抛出，这里不重试。
 */
internal enum class ControlFailure {
    INVALID_REQUEST,
    SECURE_ADB_REQUIRED,
    DISCONNECTED,
    ADB_TLS_UNAVAILABLE,
    PROTOCOL_ERROR,
    ROOT_REQUIRED,
    AUTHENTICATION_STORAGE_ERROR,
    VENDOR_AUTH_REQUIRED,
    AUTHENTICATION_REJECTED,
    TRANSPORT_ERROR,
    TIMEOUT,
}
