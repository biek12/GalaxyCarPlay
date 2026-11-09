package com.ecarx.autoservice.platform

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory

/** 仅供 KADB 2.1.1 私钥解析/公钥推导使用，绝不注册为进程级 Provider。 */
internal object AdbCrypto {
    private val provider by lazy { BouncyCastleProvider() }

    @JvmStatic fun keyFactory(algorithm: String): KeyFactory {
        require(algorithm == "RSA")
        return KeyFactory.getInstance(algorithm, provider)
    }
}
