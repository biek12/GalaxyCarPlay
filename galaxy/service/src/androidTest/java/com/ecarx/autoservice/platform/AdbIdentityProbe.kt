package com.ecarx.autoservice.platform

import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*

/** 仅隔离模拟器：真实系统 BC/BKS、并发首次 TLS、持久身份重读与双向 TLS 回环；无车辆/公网连接。 */
internal object AdbIdentityProbe {
    fun run(instrumentation: Instrumentation, result: Bundle) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "isolated_emulator_required" }
        val context = instrumentation.targetContext
        val providers = Security.getProviders().toList()
        val tlsStarted = CountDownLatch(1)
        val tlsFailure = AtomicReference<Throwable?>()
        val tls = Thread({
            try {
                tlsStarted.countDown()
                repeat(100) {
                    KeyStore.getInstance("BKS")
                    check(SSLSocketFactory.getDefault() is SSLSocketFactory)
                    SSLContext.getDefault().socketFactory
                    Thread.sleep(5)
                }
            } catch (e: Throwable) { tlsFailure.set(e) }
        }, "identity-default-tls").apply { start() }
        check(tlsStarted.await(2, TimeUnit.SECONDS))
        AdbIdentity.configure(context)
        val keyFile = File(context.noBackupFilesDir, "adb/client.pem")
        val original = keyFile.readBytes()
        val originalPub = File(context.noBackupFilesDir, "adb/client.pub").readBytes()
        val snapshot = KadbCert.ensureReady()
        // 模拟新进程对同一持久私钥的读取：不导出、不记录私钥，不生成替代身份。
        KadbCert.configure(object : KadbPrivateKeyStore {
            override fun readPrivateKeyPem() = keyFile.readBytes()
            override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) = error("existing_identity_must_not_be_replaced")
            override fun clear() = error("must_not_clear")
        })
        val reloaded = KadbCert.ensureReady()
        check(reloaded.privateKeyPem.contentEquals(original))
        check(keyFile.readBytes().contentEquals(original))
        check(File(context.noBackupFilesDir, "adb/client.pub").readBytes().contentEquals(originalPub))
        tls.join(10_000); check(!tls.isAlive); tlsFailure.get()?.let { throw it }
        check(Security.getProviders().toList().zip(providers).all { (a,b) -> a === b })
        check(Security.getProviders().size == providers.size)
        fun certificate(pem: ByteArray) = CertificateFactory.getInstance("X.509").generateCertificate(pem.inputStream())
        val cert = certificate(reloaded.certificatePem)
        cert.verify(certificate(snapshot.certificatePem).publicKey)
        val base64 = original.decodeToString().replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "").filterNot { it.isWhitespace() }
        val key = AdbCrypto.keyFactory("RSA").generatePrivate(PKCS8EncodedKeySpec(android.util.Base64.decode(base64, android.util.Base64.DEFAULT)))
        check(key is RSAPrivateCrtKey)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setKeyEntry("test", key, charArrayOf(), arrayOf(cert))
        }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, charArrayOf()) }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("test", cert) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val loopbackTls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
        val peerError = AtomicReference<Throwable?>()
        (loopbackTls.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).use { server ->
            server.needClientAuth = true; server.soTimeout = 5000
            val peer = Thread {
                try { (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5000; socket.startHandshake()
                    check(socket.session.peerCertificates.isNotEmpty())
                    check(socket.inputStream.read() == 42); socket.outputStream.write(43)
                } } catch (e: Throwable) { peerError.set(e) }
            }.apply { start() }
            (loopbackTls.socketFactory.createSocket(InetAddress.getLoopbackAddress(), server.localPort) as SSLSocket).use { socket ->
                socket.soTimeout = 5000; socket.startHandshake()
                socket.outputStream.write(42); check(socket.inputStream.read() == 43)
            }
            peer.join(6000); check(!peer.isAlive); peerError.get()?.let { throw it }
        }
        result.putString("checks", "system BC/BKS unchanged; concurrent default TLS x100; persisted RSA key unchanged; regenerated certificate verified; mutual TLS loopback passed")
        result.putString("scope", "isolated Android emulator; no vehicle ADB/Root or remote registration/poll")
    }
}
