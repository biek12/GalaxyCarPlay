package com.ecarx.autoservice.platform

import android.content.Context
import android.util.AtomicFile
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import java.io.File

/** 只读写本应用自行生成的ADB身份，不导入其他App、车辆或仓库里的认证材料。 */
internal object AdbIdentity {
    private var configured = false
    @Synchronized fun configure(context: Context) {
        if (configured) return
        // KADB 2.1.1 CertUtils 的证书转换自带局部 Provider，RSA 解析由构建期窄补丁显式指定。
        // 保留系统 BC：移除/替换进程 Provider 会与 Conscrypt 首次 TLS/BKS 初始化竞争。
        val directory = File(context.noBackupFilesDir, "adb").apply { check(isDirectory || mkdirs()) }
        val file = AtomicFile(File(directory, "client.pem"))
        val store = object : KadbPrivateKeyStore {
            override fun readPrivateKeyPem(): ByteArray? = if (!file.baseFile.exists()) null else file.openRead().use {
                check(file.baseFile.length() in 1..16384); it.readBytes()
            }
            override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
                require(privateKeyPem.size in 1..16384)
                val output = file.startWrite()
                try { output.write(privateKeyPem); file.finishWrite(output) }
                catch (error: Exception) { file.failWrite(output); throw error }
            }
            override fun clear() { file.delete() }
        }
        KadbCert.configure(store, KadbCertPolicy(subject = KadbCertPolicy.Subject(cn = "AutoService ADB Client")))
        KadbCert.ensureReady()
        // 仅导出本App新生成身份的公钥供已授权Root通道登记；私钥从不离开noBackup。
        val pair = KadbCert.javaClass.getMethod("currentKeyPair\$kadb").invoke(KadbCert)
        val publicKey = pair.javaClass.getMethod("getPublicKey").invoke(pair) as java.security.interfaces.RSAPublicKey
        val encoded = Class.forName("com.flyfishxu.kadb.cert.AndroidPubkey")
            .getMethod("encodeWithName", java.security.interfaces.RSAPublicKey::class.java, String::class.java)
            .invoke(null, publicKey, "autoservice@local") as ByteArray
        File(directory, "client.pub").writeBytes(encoded.dropLastWhile { it == 0.toByte() }.toByteArray() + byteArrayOf(10))
        configured = true
    }
}
