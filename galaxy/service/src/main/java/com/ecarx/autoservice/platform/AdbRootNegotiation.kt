package com.ecarx.autoservice.platform

import android.os.SystemClock
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

/** Worker-thread Root ordering follows EVCC DEX; success requires a fresh UID readback. */
internal class AdbRootNegotiation(
    private val work: ControlWork,
    private val authentication: RootAuthenticationStore,
    private val facts: AdbRootDeviceFacts,
    private val open: () -> AdbSession,
    private val nativeHandshakeSalt: () -> CharArray? = { null },
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val pauseMs: (Long) -> Unit = work::pause,
    private val beforeMutation: () -> Unit = {},
) {
    private var deadlineMs = 0L

    fun request(): ControlIdentity {
        deadlineMs = nowMs() + IDENTITY_DEADLINE_MS
        val before = connected { AdbObservedIdentity.read(it) }
        if (before.uid == 0) return before
        if (AdbRootAuthPath.usesVpwdPreflight(facts)) handshake(required = false)
        if (requiresHandshake(requestRoot())) {
            handshake(required = true)
            pauseMs(HANDSHAKE_RETRY_DELAY_MS)
            checkActive()
            requestRoot()
        }
        return awaitRoot()
    }

    private fun handshake(required: Boolean): Boolean {
        val nativeSecret = try { nativeHandshakeSalt() } catch (_: Exception) { null }
        val secret = try { nativeSecret ?: authentication.readVpwd() } catch (_: Exception) {
            if (!required) return false
            throw ControlTransportException(ControlFailure.AUTHENTICATION_STORAGE_ERROR)
        } ?: run {
            if (!required) return false
            throw ControlTransportException(ControlFailure.VENDOR_AUTH_REQUIRED)
        }
        try {
            checkActive()
            if (!RootCredentialRules.valid(secret)) {
                throw ControlTransportException(ControlFailure.AUTHENTICATION_REJECTED)
            }
            val escaped = "'" + String(secret).replace("'", "'\\''") + "'"
            val output = connected { session ->
                beforeMutation()
                session.shell("vpwd $escaped", 1024)
            }
            if (output.stdout.contains(AUTHENTICATION_FAILED, true) ||
                output.stderr.contains(AUTHENTICATION_FAILED, true)) {
                if (!required) return false
                throw ControlTransportException(ControlFailure.AUTHENTICATION_REJECTED)
            }
            return true
        } finally { secret.fill('\u0000') }
    }

    private fun requestRoot(): String = try { connected { session ->
        beforeMutation()
        session.root()
    } } catch (error: IOException) {
        checkActive()
        if (error is ControlTransportException && error.reason !in TRANSIENT_FAILURES) throw error
        // adbd may restart before CLSE; only an actual UID readback can establish success.
        ""
    }

    private fun awaitRoot(): ControlIdentity {
        checkActive()
        pauseMs(ADBD_SETTLE_DELAY_MS)
        repeat(AdbRootAuthPath.verificationAttempts(facts)) {
            checkActive()
            pauseMs(VERIFY_INTERVAL_MS)
            try {
                val identity = connected { AdbObservedIdentity.read(it) }
                if (identity.uid == 0) return identity
            } catch (error: IOException) {
                checkActive()
                if (error is ControlTransportException && error.reason !in TRANSIENT_FAILURES) throw error
            }
        }
        throw ControlTransportException(ControlFailure.ROOT_REQUIRED)
    }

    private fun requiresHandshake(reply: String): Boolean {
        val lower = reply.lowercase(Locale.ROOT)
        return lower.contains(USE_ADB_SHELL_VPWD) ||
            (lower.contains(BEFORE_AUTHENTICATION) && lower.contains(VPWD))
    }

    private fun <T> connected(operation: (AdbSession) -> T): T {
        checkActive()
        return open().use {
            it.connect()
            checkActive()
            operation(it).also { checkActive() }
        }
    }

    private fun checkActive() {
        if (work.cancelled.get()) throw IOException("cancelled")
        if (deadlineMs == 0L || nowMs() >= deadlineMs) throw SocketTimeoutException("control deadline")
    }

    private companion object {
        val TRANSIENT_FAILURES = setOf(ControlFailure.DISCONNECTED, ControlFailure.TRANSPORT_ERROR,
            ControlFailure.TIMEOUT)
        const val AUTHENTICATION_FAILED = "Authentication failed"
        const val BEFORE_AUTHENTICATION = "before authentication"
        const val USE_ADB_SHELL_VPWD = "use adb shell vpwd"
        const val VPWD = "vpwd"
        const val HANDSHAKE_RETRY_DELAY_MS = 500L
        const val ADBD_SETTLE_DELAY_MS = 2_000L
        const val VERIFY_INTERVAL_MS = 1_000L
        const val IDENTITY_DEADLINE_MS = 20_000L
    }
}
