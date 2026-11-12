package com.ecarx.autoservice.remote

import com.ecarx.autoservice.R
import java.security.KeyManagementException
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

/**
 * 只发布固定状态，不把地址、凭据或异常消息带到界面/快照。
 *
 * [failure] 分类链路原样保留：初始化类异常优先，其次任一环为 SSL 失败，最后才是普通重连。
 * [labelResource] 是唯一文案来源，页面不再各自维护一份中文 when。
 */
internal object RemoteConnectionStatus {
    /** 把异常链归并为三个固定状态之一；链路有环时安全退出。 */
    fun failure(error: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        var tls = false
        while (cause != null && seen.add(cause)) {
            if (cause is NoSuchAlgorithmException || cause is KeyStoreException || cause is KeyManagementException) {
                return "tls_initialization_failed"
            }
            if (cause is SSLException) tls = true
            cause = cause.cause
        }
        return if (tls) "tls_failed" else "reconnecting"
    }

    /** 状态到页面文案的资源映射；未知/为空取“正在连接服务端…”。 */
    fun labelResource(state: String?): Int =
        when (state) {
            "connected" -> R.string.service_remote_state_connected
            "waiting_code" -> R.string.service_remote_state_waiting_code
            "tls_initialization_failed" -> R.string.service_remote_state_tls_initialization_failed
            "tls_failed" -> R.string.service_remote_state_tls_failed
            "reconnecting" -> R.string.service_remote_state_reconnecting
            "expired" -> R.string.service_remote_state_expired
            else -> R.string.service_remote_state_unknown
        }
}
