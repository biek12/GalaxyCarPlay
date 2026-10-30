package com.ecarx.autoservice.platform

import java.io.Closeable
import java.io.File
import java.util.UUID

/** 仅操作本服务目录。已安装程序按校验和/owner key检查后唤起，确需安装才创建NFS暂存。 */
internal class QnxReceiverRemote(
    private val execute: (String) -> String,
    private val binary: ByteArray,
    private val key: ByteArray,
    private val sha: String,
) : QnxReceiverLifecycle.Port, Closeable {
    private val directory = "/var/data/autoservice-hab"
    private val shared = "/data/vendor/nfs/nfs_ota"
    private val stageName = "autoservice-" + UUID.randomUUID().toString()
    private val stage = File(shared, stageName)
    private var installing = false
    private val crc = PosixChecksum.calculate(binary)
    private val keyCrc = PosixChecksum.calculate(key)
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    override fun inspect(): QnxReceiverLifecycle.State {
        val body = "owned=0; if [ -f $directory/receiver.pid ]; then p=\$(cat $directory/receiver.pid); " +
            "case \"\$p\" in ''|*[!0-9]*) exit 21;; esac; " +
            "if kill -0 \"\$p\" 2>/dev/null; then a=\$(pidin -p \"\$p\" ar) || exit 22; " +
            "case \"\$a\" in *$directory/receiver*) owned=1;; esac; fi; fi; " +
            "if [ -f $directory/version ] && [ \"\$(cat $directory/version)\" = ${quote(sha)} ] && " +
            "cksum $directory/receiver | grep '^$crc[[:space:]]*${binary.size}[[:space:]]' >/dev/null && " +
            "cksum $directory/owner.key | grep '^$keyCrc[[:space:]]*32[[:space:]]' >/dev/null; then " +
            "if [ \"\$owned\" = 1 ] && grep RECEIVER_LISTEN_SESSION $directory/receiver.log >/dev/null; then " +
            "echo; echo AUTOSERVICE_RECEIVER_LISTENING; else echo; echo AUTOSERVICE_RECEIVER_INSTALLED; fi; " +
            "else echo; echo AUTOSERVICE_RECEIVER_NEEDS_INSTALL; fi"
        val markers = execute(body).lineSequence().map { it.trim() }
            .filter { it in setOf("AUTOSERVICE_RECEIVER_LISTENING", "AUTOSERVICE_RECEIVER_INSTALLED", "AUTOSERVICE_RECEIVER_NEEDS_INSTALL") }.toList()
        check(markers.size == 1) { "qnx_receiver_inventory_invalid" }
        return when (markers.single()) {
            "AUTOSERVICE_RECEIVER_LISTENING" -> QnxReceiverLifecycle.State.LISTENING
            "AUTOSERVICE_RECEIVER_INSTALLED" -> QnxReceiverLifecycle.State.INSTALLED
            else -> QnxReceiverLifecycle.State.NEEDS_INSTALL
        }
    }
    override fun stopOwned() { execute(stopCommand()) }
    override fun install() {
        check(stage.mkdir()) { "nfs_stage_unavailable" }
        File(stage, "receiver").writeBytes(binary)
        File(stage, "owner.key").apply {
            writeBytes(key)
            setReadable(false, false); setReadable(true, true)
            setWritable(false, false); setWritable(true, true)
        }
        execute("umask 077; mkdir -p $directory && cp /otaupdate/$stageName/receiver $directory/receiver.new && cp /otaupdate/$stageName/owner.key $directory/owner.key && chmod 700 $directory/receiver.new && chmod 600 $directory/owner.key")
        installing = true
    }
    override fun verify() {
        val path = "$directory/receiver" + if (installing) ".new" else ""
        val verified = execute("cksum $path")
        check(Regex("(?m)^$crc[ \\t]+${binary.size}[ \\t]+").containsMatchIn(verified)) { "qnx_binary_checksum_mismatch" }
        check(execute("$path self-test").contains("SELFTEST_PASS")) { "qnx_self_test_failed" }
        check(Regex("QNX_DISPLAY_ID=2\\r?\\nWIDTH=1920\\r?\\nHEIGHT=532").containsMatchIn(execute("$path inventory"))) { "qnx_display_profile_mismatch" }
    }
    override fun start() {
        val promote = if (installing) "mv $directory/receiver.new $directory/receiver || exit 31; echo ${quote(sha)} > $directory/version || exit 32; " else ""
        execute(promote + "(trap '' HUP; exec $directory/receiver serve $directory/owner.key 2 1920 532 4) >$directory/receiver.log 2>&1 </dev/null & p=\$!; " +
            "echo \"\$p\" > $directory/receiver.pid; sleep 1; kill -0 \"\$p\" && grep RECEIVER_LISTEN_SESSION=1 $directory/receiver.log >/dev/null")
    }
    private fun stopCommand() = "if [ -f $directory/receiver.pid ]; then p=\$(cat $directory/receiver.pid); case \"\$p\" in ''|*[!0-9]*) exit 21;; esac; " +
        "if kill -0 \"\$p\" 2>/dev/null; then a=\$(pidin -p \"\$p\" ar) || exit 22; " +
        "case \"\$a\" in *$directory/receiver*) kill -TERM \"\$p\" || exit 23; sleep 1; kill -0 \"\$p\" 2>/dev/null && exit 24;; esac; fi; " +
        "rm -f $directory/receiver.pid; fi"
    override fun close() { stage.listFiles()?.forEach { it.delete() }; stage.delete() }
}
