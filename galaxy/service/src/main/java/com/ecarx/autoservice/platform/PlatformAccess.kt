package com.ecarx.autoservice.platform

import android.content.Context
import android.os.Build
import android.os.Process
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import com.ecarx.autoservice.settings.ServiceFeatures

/** 服务层独占连接操作。星枢的TLS/AUTH/Root流程与业务Shell共用接入实现，UI不创建第二个owner。 */
internal class PlatformAccess(private val context: Context) : Closeable {
    @Volatile private var current: ControlWork? = null
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    private val settings = LocalAdbEndpointSettings(context)
    private val resolver = LocalAdbEndpointResolver(settings)
    @Volatile var foundationEvidence: String = "{}"
        private set
    fun endpoint() = settings.read().let { it.host to it.port }
    private fun <T> operation(timeout: Long = 30000, action: (ControlWork) -> T): T {
        check(!closed) { "platform_closed" }
        val work = ControlWork(); current = work
        val deadline = PlatformDeadlines.after(timeout) { work.cancel() }
        try { return action(work) }
        finally { deadline.cancel(false); work.close(); if (current === work) current = null }
    }
    private fun connection(work: ControlWork): AdbConnection {
        check(ServiceFeatures.read(context).adb) { "adb_disabled" }
        return AdbConnection(context, work, resolver)
    }
    /** ADB单独开启时只连接和回读身份，不启动helper、不请求adbd root。 */
    fun ensureAdb(): String {
        check(ServiceFeatures.read(context).adb) { "adb_disabled" }
        foundationEvidence = JSONObject().put("state", "checking").toString()
        return try {
            AdbIdentity.configure(context)
            operation { work ->
                WirelessAdbStartupActivator(context).enable { !closed && !work.cancelled.get() && ServiceFeatures.read(context).adb }
                connection(work).use { adb ->
                    adb.connect()
                    val uid = AdbObservedIdentity.read(adb).uid
                    work.checkActive()
                    check(!closed && ServiceFeatures.read(context).adb) { "adb_disabled" }
                    foundationEvidence = JSONObject().put("state", "adb_connected").put("adbUid", uid)
                        .put("tlsPort", checkNotNull(adb.connectedPort)).put("rootUid", -1)
                        .put("identitySource", "autoservice_private_adb").toString()
                }
            }
            "adb_ready"
        } catch (error: Exception) {
            foundationEvidence = JSONObject().put("state", "failed").put("stage", "adb_connection_failed").toString()
            com.ecarx.autoservice.ServiceTrace.event("adb_connection_failed", error)
            throw error
        }
    }
    fun inspectAdb(): String = operation { work -> connection(work).use { it.connect(); "ADB UID=${AdbObservedIdentity.read(it).uid}" } }
    fun requestAdbRoot(): String = operation { work -> "ADB UID=${negotiateRoot(work).uid}" }
    private fun negotiateRoot(work: ControlWork): ControlIdentity {
        check(ServiceFeatures.read(context).root) { "root_disabled" }
        return AdbRootNegotiation(work,
        object : RootAuthenticationStore { override fun readVpwd(): CharArray? = null },
        AdbRootDeviceFacts(Build.MODEL, Build.DEVICE, Build.FINGERPRINT, Build.VERSION.SDK_INT),
        { connection(work) }).request()
    }
    private fun rootCommand(): String {
        val apk = context.applicationInfo.sourceDir
        val classpath = (listOf(apk) + listOf("/system/framework/ecarx.car.jar", "/system/framework/android.car.jar").filter { File(it).exists() }).joinToString(":")
        val model = context.getSharedPreferences("platform", 0).getString("model", "L6")!!
        require(model in setOf("L6", "L7"))
        val owner = RootOwner.current()
        val hash = RootServiceAuthentication.signingCertificateHash(context).joinToString("") { "%02x".format(it) }
        val arguments = listOf(owner.uid.toString(), owner.pid.toString(), owner.started, owner.socketName, model, apk, hash).joinToString(" ", transform = ::quote)
        return "(trap '' HUP; CLASSPATH=${quote(classpath)} /system/bin/app_process /system/bin --nice-name=autoservice-root com.ecarx.autoservice.platform.RootServiceMain $arguments) </dev/null >/dev/null 2>&1 &"
    }
    private fun awaitRoot(work: ControlWork) {
        repeat(12) {
            work.checkActive()
            if (runCatching { RootServiceClient.healthy(context) }.getOrDefault(false)) {
                val readback = RootServiceClient.request(context, 3, JSONObject().put("command", "id -u").put("timeoutMillis", 2000), 3000)
                check(readback.optInt("exitCode", -1) == 0 && readback.optString("output").trim() == "0") { "root_identity_failed" }
                return
            }
            work.pause(500)
        }
        error("root_service_unavailable")
    }
    fun ensureRoot(interactive: Boolean = false): String {
        check(ServiceFeatures.read(context).root) { "root_disabled" }
        val ticket = generation
        foundationEvidence = JSONObject().put("state", "checking").toString()
        com.ecarx.autoservice.ServiceTrace.event("foundation_checking")
        return try {
            prepareRoot(interactive)
            if (!ServiceFeatures.read(context).adb) {
                check(!closed && ticket == generation && ServiceFeatures.read(context).root) { "foundation_cancelled" }
                foundationEvidence = JSONObject().put("state", "root_connected").put("rootUid", 0)
                    .put("adbUid", -1).put("tlsPort", 0).toString()
                return "root_ready"
            }
            operation(55000) { work ->
                fun active() = !closed && ticket == generation && !work.cancelled.get() && ServiceFeatures.read(context).let { it.adb && it.root }
                check(active()) { "foundation_cancelled" }
                // helper存在不代表本App的TLS公钥已经可用；先恢复本App权限与本车已核验的TLS开关。
                prepareTls(work, ::active)
                negotiateRoot(work)
                connection(work).use { adb ->
                    adb.connect()
                    val adbUid = AdbObservedIdentity.read(adb).uid
                    check(adbUid == 0) { "adb_root_not_granted" }
                    val root = RootServiceClient.request(context, 3, JSONObject().put("command", "id -u").put("timeoutMillis", 2000), 3000)
                    check(root.optInt("exitCode", -1) == 0 && root.optString("output").trim() == "0") { "root_identity_failed" }
                    check(active()) { "foundation_cancelled" }
                    foundationEvidence = JSONObject().put("state", "verified").put("adbUid", adbUid)
                        .put("rootUid", 0).put("tlsPort", checkNotNull(adb.connectedPort))
                        .put("verifiedAtElapsedMillis", android.os.SystemClock.elapsedRealtime())
                        .put("identitySource", "autoservice_private_adb").toString()
                }
                "root_ready"
            }
        } catch (error: Exception) {
            com.ecarx.autoservice.ServiceTrace.event("foundation_failed", error)
            foundationEvidence = JSONObject().put("state", "failed").put("stage",
                error.message?.takeIf { it.matches(Regex("[a-z_]+")) } ?: "foundation_verification_failed").toString()
            throw error
        }
    }
    private fun prepareTls(work: ControlWork, active: () -> Boolean) {
        work.checkActive()
        AppPermissionSetup(context).repair(active)
        work.checkActive()
        check(active() && ServiceFeatures.read(context).adb) { "adb_disabled" }
        val tls = RootServiceClient.request(context, 3, JSONObject().put("command",
            "if [ \"\$(getprop persist.adb.tls_server.enable)\" != 1 ]; then setprop persist.adb.tls_server.enable 1; fi; " +
            "[ \"\$(getprop persist.adb.tls_server.enable)\" = 1 ]").put("timeoutMillis", 5000), 7000)
        check(tls.optInt("exitCode", -1) == 0) { "adb_tls_enable_failed" }
        check(WirelessAdbStartupActivator(context).enable(active).startsWith("status=enabled;")) { "adb_tls_not_ready" }
        work.checkActive()
    }
    private fun prepareRoot(interactive: Boolean = false): String {
        val ticket = generation
        fun active() = !closed && ticket == generation && ServiceFeatures.read(context).root
        fun checkActive() { if (!active()) throw java.io.IOException("cancelled") }
        checkActive()
        val adbEnabled = ServiceFeatures.read(context).adb
        if (adbEnabled) AdbIdentity.configure(context)
        if (runCatching { RootServiceClient.healthy(context) }.getOrDefault(false)) {
            checkActive()
            if (adbEnabled) {
                val key = File(context.noBackupFilesDir, "adb/client.pub").readText().trim()
                RootServiceClient.request(context, 7, JSONObject().put("publicKey", key), 10000)
            }
            operation { awaitRoot(it) }
            return "root_ready"
        }
        checkActive()
        if (interactive || context.getSharedPreferences("platform", 0).getString("rootTransport", "ADB") == "SU") {
            val available = runCatching { startUsingSu() }.isSuccess
            if (available) return "root_ready"
        }
        // ADB关闭时只复用既有、已在本车验证的本机Root入口启动自有helper；不登记公钥或打开TLS。
        if (!adbEnabled) return operation(55000) { work ->
            checkActive()
            AndroidRootBootstrap.launch(work, rootCommand())
            awaitRoot(work)
            checkActive()
            "root_ready"
        }
        // 首次安装没有新App授权时，沿本车已核验的Android本机root调试入口自动建立helper。
        // 不要求用户重复星枢的配对/赋权；后续开机优先使用已配置好的TLS ADB。
        checkActive()
        val needsSetup = context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsSetup && runCatching { bootstrap() }.isSuccess) return "root_ready"
        checkActive()
        WirelessAdbStartupActivator(context).enable(::active)
        checkActive()
        return try { operation(55000) { work ->
            negotiateRoot(work)
            connection(work).use { adb ->
                adb.connect()
                check(AdbObservedIdentity.read(adb).uid == 0) { "adb_root_not_granted" }
                check(adb.shell(rootCommand(), 1024).exitCode == 0) { "root_launch_failed" }
            }
            awaitRoot(work)
            "root_ready"
        } } catch (error: Exception) {
            checkActive()
            // 这里只使用本车已有、独立验证UID0的入口；不会尝试未知主机或猜测认证。
            try { bootstrap(); "root_ready" } catch (_: Exception) { throw error }
        }
    }
    private fun bootstrap() = operation(55000) { work ->
        AndroidRootBootstrap.launch(work, rootCommand())
        awaitRoot(work)
        work.checkActive()
        val publicKey = File(context.noBackupFilesDir, "adb/client.pub").readText().trim()
        val result = RootServiceClient.request(context, 7, JSONObject().put("publicKey", publicKey), 10000)
        check(result.optString("state") in setOf("authorized", "already_authorized")) { "adb_bootstrap_authorization_failed" }
        prepareTls(work) { !work.cancelled.get() && !closed && ServiceFeatures.read(context).let { it.adb && it.root } }
        work.checkActive()
        negotiateRoot(work) // 首次自动登记后也验证TLS/AUTH和adbd的UID0，不只验证Telnet helper。
    }
    fun startUsingAdb(): String = ensureRoot()
    fun startUsingSu(): String = operation(15000) { work ->
        check(ServiceFeatures.read(context).root) { "root_disabled" }
        val process = ProcessBuilder("su", "-c", rootCommand()).redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
            .redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        val owned = Closeable { process.destroy() }.also(work::own)
        try {
            check(process.waitFor(8, TimeUnit.SECONDS) && process.exitValue() == 0) { "su_rejected" }
            awaitRoot(work); "root_ready"
        } finally { work.release(owned) }
    }
    fun pair(port: Int, code: String) {
        check(ServiceFeatures.read(context).adb) { "adb_disabled" }
        require(port in 1..65535 && code.matches(Regex("[0-9]{6}")))
        AdbIdentity.configure(context)
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(15000) { com.flyfishxu.kadb.Kadb.pair(endpoint().first, port, code) } }
    }
    fun executeShell(command: String, timeout: Int, transport: String): JSONObject {
        require(command.length in 1..8192 && '\u0000' !in command && timeout in 100..30000)
        val features = ServiceFeatures.read(context)
        check(if (transport == "ROOT") features.root else features.adb) { "transport_disabled" }
        return if (transport == "ROOT") operation(timeout + 5000L) { work ->
            RootServiceClient.request(context, 3, JSONObject().put("command", command).put("timeoutMillis", timeout), timeout + 2000, work)
        }
        else {
            require(transport == "ADB")
            operation(timeout + 20000L) { work -> connection(work).use { it.execute(command, timeout) } }
        }
    }
    fun cancel() { ++generation; current?.cancel() }
    override fun close() { closed = true; cancel() }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
