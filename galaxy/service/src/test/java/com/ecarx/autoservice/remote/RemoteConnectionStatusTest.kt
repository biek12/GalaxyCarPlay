package com.ecarx.autoservice.remote

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketException
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLHandshakeException

class RemoteConnectionStatusTest {
    @Test fun tlsInitializationFailureSurvivesSocketWrappingAndHasDistinctUi() {
        val error = SocketException().apply { initCause(NoSuchAlgorithmException("default SSL", KeyStoreException("BKS"))) }
        assertEquals("tls_initialization_failed", RemoteConnectionStatus.failure(error))
        assertTrue(RemoteConnectionStatus.label(RemoteConnectionStatus.failure(error)).contains("TLS 初始化失败"))
    }
    @Test fun handshakeAndOrdinaryReconnectRemainDistinct() {
        assertEquals("tls_failed", RemoteConnectionStatus.failure(SSLHandshakeException("private detail")))
        assertEquals("reconnecting", RemoteConnectionStatus.failure(SocketException("private detail")))
        assertFalse(RemoteConnectionStatus.label("tls_failed").contains("private detail"))
        assertFalse(RemoteConnectionStatus.label("reconnecting").contains("正在连接服务端"))
    }
}
