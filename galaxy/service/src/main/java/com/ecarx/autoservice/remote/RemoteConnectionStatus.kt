package com.ecarx.autoservice.remote

import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLException

/** 只发布固定状态，不把地址、凭据或异常消息带到界面/快照。 */
internal object RemoteConnectionStatus {
    fun failure(error: Throwable): String {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        var tls = false
        while (cause != null && seen.add(cause)) {
            if (cause is NoSuchAlgorithmException || cause is KeyStoreException || cause is java.security.KeyManagementException)
                return "tls_initialization_failed"
            if (cause is SSLException) tls = true
            cause = cause.cause
        }
        return if (tls) "tls_failed" else "reconnecting"
    }

    fun label(state: String?): String = when (state) {
        "connected" -> "远程连接已建立"
        "waiting_code" -> "等待验证码配对"
        "tls_initialization_failed" -> "TLS 初始化失败，正在重试 · 请查看诊断日志"
        "tls_failed" -> "TLS 连接失败，正在重试 · 请查看诊断日志"
        "reconnecting" -> "远程连接中断，正在重连…"
        "expired" -> "远程授权已到期"
        else -> "正在连接服务端…"
    }
}
