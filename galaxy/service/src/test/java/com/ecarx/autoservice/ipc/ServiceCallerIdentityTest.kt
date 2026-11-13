package com.ecarx.autoservice.ipc

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 只替换四个系统函数，断言打到生产 [ServiceCallerIdentity]。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ServiceCallerIdentityTest {
    @Test
    fun `自身UID直接通过且每次重新读取不检查签名也不读包名`() {
        var reads = 0
        val script = intArrayOf(1000, 2401)
        var signatures = 0
        var packages = 0
        val identity =
            ServiceCallerIdentity(
                callingUid = {
                    val uid = script[reads]
                    reads += 1
                    uid
                },
                processUid = { 1000 },
                checkSignatures = { caller, own ->
                    signatures += 1
                    assertEquals(2401, caller)
                    assertEquals(1000, own)
                    PackageManager.SIGNATURE_NO_MATCH
                },
                packagesForUid = {
                    packages += 1
                    arrayOf("com.ecarx.autoservice")
                },
            )
        assertEquals(1000, identity.requireUid())
        assertEquals(1, reads)
        assertEquals(0, signatures)
        assertEquals(0, packages)
        val rejected = assertThrows(IllegalStateException::class.java) { identity.requireUid() }
        assertEquals("client_signature_rejected", rejected.message)
        assertEquals(2, reads)
        assertEquals(1, signatures)
        assertEquals(0, packages)
    }

    @Test
    fun `异UID同签名返回调用UID且只检查这一对`() {
        var reads = 0
        val checks = mutableListOf<Pair<Int, Int>>()
        var packages = 0
        val identity =
            ServiceCallerIdentity(
                callingUid = {
                    reads += 1
                    2401
                },
                processUid = { 1000 },
                checkSignatures = { caller, own ->
                    checks += caller to own
                    PackageManager.SIGNATURE_MATCH
                },
                packagesForUid = {
                    packages += 1
                    arrayOf("com.third.party")
                },
            )
        assertEquals(2401, identity.requireUid())
        assertEquals(1, reads)
        assertEquals(listOf(2401 to 1000), checks)
        assertEquals(0, packages)
        assertEquals("", identity.source())
        assertEquals(listOf(2401 to 1000), checks)
        assertEquals(1, packages)
    }

    @Test
    fun `异签名即使包在诊断列表内也拒绝且消息不变`() {
        var signatures = 0
        var packages = 0
        val identity =
            ServiceCallerIdentity(
                callingUid = { 2401 },
                processUid = { 1000 },
                checkSignatures = { caller, own ->
                    signatures += 1
                    assertEquals(2401, caller)
                    assertEquals(1000, own)
                    PackageManager.SIGNATURE_NO_MATCH
                },
                packagesForUid = {
                    packages += 1
                    arrayOf("com.ecarx.carplay", "com.example.other")
                },
            )
        val rejected = assertThrows(IllegalStateException::class.java) { identity.requireUid() }
        assertEquals("client_signature_rejected", rejected.message)
        assertEquals(1, signatures)
        assertEquals(0, packages)
        assertEquals("com.ecarx.carplay", identity.source())
        assertEquals(1, signatures)
        assertEquals(1, packages)
    }

    @Test
    fun `来源把null当空并保留过滤后的原顺序和重复`() {
        var signatures = 0
        var seenUid = -1
        val missing =
            ServiceCallerIdentity(
                callingUid = { 3000 },
                processUid = { 1000 },
                checkSignatures = { _, _ ->
                    signatures += 1
                    PackageManager.SIGNATURE_MATCH
                },
                packagesForUid = { uid ->
                    seenUid = uid
                    null
                },
            )
        assertEquals("", missing.source())
        assertEquals(3000, seenUid)
        assertEquals(0, signatures)
        val ordered =
            ServiceCallerIdentity(
                callingUid = { 3001 },
                processUid = { 1000 },
                checkSignatures = { _, _ ->
                    signatures += 1
                    PackageManager.SIGNATURE_NO_MATCH
                },
                packagesForUid = {
                    arrayOf(
                        "com.example.other",
                        "com.galaxy.autocluster",
                        "com.ecarx.carplay",
                        "com.ecarx.carplay",
                        "com.galaxy.autorecord",
                        "com.ecarx.autoservice",
                        "com.example.other",
                    )
                },
            )
        assertEquals(
            "com.galaxy.autocluster,com.ecarx.carplay,com.ecarx.carplay,com.galaxy.autorecord,com.ecarx.autoservice",
            ordered.source(),
        )
        assertEquals(0, signatures)
        val unknown =
            ServiceCallerIdentity(
                callingUid = { 9 },
                processUid = { 1 },
                checkSignatures = { _, _ -> error("诊断列表不能变成授权") },
                packagesForUid = { arrayOf("com.example.other", "com.example.other") },
            )
        assertEquals("", unknown.source())
    }
}
