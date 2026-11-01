package com.ecarx.autoservice.platform

import android.content.Context
import android.util.AtomicFile
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security

/** 只读写本应用自行生成的ADB身份，不导入其他App、车辆或仓库里的认证材料。 */
internal object AdbIdentity {
    private var configured = false
    @Synchronized fun configure(context: Context) {
        if (configured) return
        if (Security.getProvider("BC")?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
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
