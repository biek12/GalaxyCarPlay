package com.ecarx.autoservice.platform.adb

/**
 * 端口探测的三种结果。只描述 STLS 之前的观察，不表示已经认证。
 */
internal enum class AdbTlsProbe {
    /** 对端先返回 STLS。 */
    SECURE,

    /** 已经连上，但回复不是 STLS。 */
    NOT_SECURE,

    /** 在超时前没有连上。 */
    UNREACHABLE,
}
