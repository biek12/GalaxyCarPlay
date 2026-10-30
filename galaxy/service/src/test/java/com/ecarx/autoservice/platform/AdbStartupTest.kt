package com.ecarx.autoservice.platform

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AdbStartupTest {
    private val facts = AdbRootDeviceFacts("G733", "g733", "local-test", 30)
    @Test fun rootRestartEofRequiresFreshUidAndReconnects() {
        var uid = 2000; var opened = 0; var roots = 0; var clock = 0L
        val negotiation = AdbRootNegotiation(ControlWork(), object : RootAuthenticationStore { override fun readVpwd(): CharArray? = null }, facts,
            open = { ++opened; object : AdbSession {
                override fun connect() {}
                override fun shell(command: String, limit: Int) = ShellV2Output(uid.toString(), "", 0)
                override fun root(): String { ++roots; uid = 0; throw IOException("restarted") }
                override fun close() {}
            } }, nowMs = { clock }, pauseMs = { clock += it })
        assertEquals(0, negotiation.request().uid); assertEquals(1, roots); assertTrue(opened >= 3)
    }
    @Test fun successTextWithoutUidZeroIsRejected() {
        var clock = 0L
        val negotiation = AdbRootNegotiation(ControlWork(), object : RootAuthenticationStore { override fun readVpwd(): CharArray? = null }, facts,
            open = { object : AdbSession {
                override fun connect() {}
                override fun shell(command: String, limit: Int) = ShellV2Output("2000", "", 0)
                override fun root() = "restarting adbd as root"
                override fun close() {}
            } }, nowMs = { clock }, pauseMs = { clock += it })
        try { negotiation.request(); fail("text is not identity") }
        catch (failure: ControlTransportException) { assertEquals(ControlFailure.ROOT_REQUIRED, failure.reason) }
    }
    @Test fun alreadyRootDoesNotRestartAdbd() {
        val negotiation = AdbRootNegotiation(ControlWork(), object : RootAuthenticationStore { override fun readVpwd(): CharArray? = null }, facts,
            open = { object : AdbSession {
                override fun connect() {}
                override fun shell(command: String, limit: Int) = ShellV2Output("0", "", 0)
                override fun root(): String = error("must not restart")
                override fun close() {}
            } }, nowMs = { 1L })
        assertEquals(0, negotiation.request().uid)
    }
    @Test fun tlsCandidateDiscoveryIsBoundedAndRejectsNonListeners() {
        val lines = (0..100).asSequence().map { "0: 0100007F:${(11000 + it).toString(16)} 0:0 0A" }
        assertEquals(64, AdbTlsPortDiscovery.listenerPorts(lines).size)
        assertTrue(AdbTlsPortDiscovery.listenerPorts(sequenceOf("0: 0100007F:8ABD 0:0 01")).isEmpty())
    }
}
