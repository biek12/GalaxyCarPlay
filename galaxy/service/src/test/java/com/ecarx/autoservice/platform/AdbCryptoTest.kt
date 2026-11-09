package com.ecarx.autoservice.platform

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Security
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

class AdbCryptoTest {
    @Test fun localProviderParsesPersistedKeyAndDerivesSamePublicKeyWithoutChangingJca() {
        val providers = Security.getProviders().toList()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val factory = AdbCrypto.keyFactory("RSA")
        val parsed = factory.generatePrivate(PKCS8EncodedKeySpec(pair.private.encoded)) as RSAPrivateCrtKey
        val publicKey = factory.generatePublic(RSAPublicKeySpec(parsed.modulus, parsed.publicExponent))
        assertArrayEquals(pair.private.encoded, parsed.encoded)
        assertArrayEquals(pair.public.encoded, publicKey.encoded)
        assertEquals(providers.size, Security.getProviders().size)
        Security.getProviders().forEachIndexed { index, provider -> assertSame(providers[index], provider) }
    }
}
