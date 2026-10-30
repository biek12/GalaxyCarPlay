package com.ecarx.autoservice.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** EVCC-compatible challenge response; the public signer digest is not a secret and peer UID is checked separately. */
internal object RootServiceAuthentication {
    private const val CHALLENGE_SIZE = 32
    private const val PACKAGE_NAME = "com.ecarx.autoservice"

    /** Reads the public signing-certificate digest; it is not a private key or secret. */
    fun signingCertificateHash(context: Context): ByteArray {
        val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            legacySigningCertificate(context)
        }
            ?: throw IOException("Installed app signing certificate is unavailable")
        return MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
    }

    @Suppress("DEPRECATION")
    private fun legacySigningCertificate(context: Context) = context.packageManager
        .getPackageInfo(PACKAGE_NAME, PackageManager.GET_SIGNATURES)
        .signatures?.firstOrNull()

    /** Client reads the server challenge and returns the same 32-byte HMAC frame as EVCC. */
    fun answerChallenge(input: DataInputStream, output: DataOutputStream, certificateHash: ByteArray) {
        val challenge = readFrame(input)
        val response = response(certificateHash, challenge)
        output.writeInt(response.size)
        output.write(response)
        output.flush()
    }

    /** Server sends a fresh challenge and rejects missing, malformed or incorrect responses. */
    fun verifyClient(input: DataInputStream, output: DataOutputStream, certificateHash: ByteArray) {
        val challenge = ByteArray(CHALLENGE_SIZE).also(SecureRandom()::nextBytes)
        output.writeInt(challenge.size)
        output.write(challenge)
        output.flush()
        val received = readFrame(input)
        if (!MessageDigest.isEqual(response(certificateHash, challenge), received)) {
            throw IOException("RootService client authentication failed")
        }
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        if (input.readInt() != CHALLENGE_SIZE) throw IOException("Invalid RootService auth frame length")
        return ByteArray(CHALLENGE_SIZE).also(input::readFully)
    }

    private fun response(certificateHash: ByteArray, challenge: ByteArray): ByteArray {
        require(certificateHash.size == CHALLENGE_SIZE && challenge.size == CHALLENGE_SIZE)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(certificateHash, "HmacSHA256"))
            doFinal(challenge)
        }
    }
}
