package com.ecarx.autoservice.remote

import com.ecarx.autoservice.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.SocketException
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLHandshakeException

/**
 * 远程连接错误分类用例：分类函数不变，文案改为资源方法。
 *
 * RS-01：TLS 初始化/连接失败/重连三态各自对应固定资源，且不携带合成异常的私密详情。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RemoteConnectionStatusTest {
    /** 渲染资源文案，用于检查是否泄漏异常详情。 */
    private fun label(state: String): String =
        RuntimeEnvironment.getApplication().getString(RemoteConnectionStatus.labelResource(state))

    @Test
    fun tlsInitializationFailureSurvivesSocketWrappingAndHasDistinctUi() {
        val error =
            SocketException().apply {
                initCause(
                    NoSuchAlgorithmException("default SSL", KeyStoreException("BKS")),
                )
            }
        assertEquals("tls_initialization_failed", RemoteConnectionStatus.failure(error))
        assertEquals(
            R.string.service_remote_state_tls_initialization_failed,
            RemoteConnectionStatus.labelResource(RemoteConnectionStatus.failure(error)),
        )
        assertTrue(label("tls_initialization_failed").contains("TLS 初始化失败"))
    }

    @Test
    fun handshakeAndOrdinaryReconnectRemainDistinctAndRedacted() {
        assertEquals("tls_failed", RemoteConnectionStatus.failure(SSLHandshakeException("private detail")))
        assertEquals("reconnecting", RemoteConnectionStatus.failure(SocketException("private detail")))
        assertEquals(R.string.service_remote_state_tls_failed, RemoteConnectionStatus.labelResource("tls_failed"))
        assertEquals(
            R.string.service_remote_state_reconnecting,
            RemoteConnectionStatus.labelResource("reconnecting"),
        )
        assertFalse(label("tls_failed").contains("private detail"))
        assertFalse(label("reconnecting").contains("private detail"))
        assertFalse(label("reconnecting").contains("正在连接服务端"))
        assertEquals(R.string.service_remote_state_unknown, RemoteConnectionStatus.labelResource(null))
    }
}
