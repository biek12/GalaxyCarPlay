package com.ecarx.autoservice.platform.hook

import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 串行部署、固定材料校验、唯一进程和独立 guard 都在成功回执之前完成。
 *
 * 调用线程由 [QnxClusterHook] 的串行锁持有。[execute] 的连接不在这里关闭。
 * [close] 只删除本次创建的暂存目录，重复关闭是空操作。
 */
internal class QnxClusterRemote(
    private val execute: (String) -> String,
    private val files: Map<String, ByteArray>,
    private val version: String,
    private val leaseActive: (Long) -> Boolean,
    private val stageRoot: File = File("/data/vendor/nfs/nfs_ota"),
) : AutoCloseable {
    private val directory = "/var/data/autoservice-cluster"
    private var stage: File? = null
    private val pids =
        "pidin -F '%a %A' | awk '\$1+0>1 && (\$2==\"./cluster-hmi\" || " +
            "\$2==\"cluster-hmi\" || " +
            "\$2==\"/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/cluster-hmi\") {print \$1}'"
    private val checks =
        files.filterKeys { it != "build.json" }.map { (name, bytes) ->
            "cksum $directory/$name | grep '^[[:space:]]*${PosixChecksum.calculate(bytes)}[[:space:]]*" +
                "${bytes.size}[[:space:]]' >/dev/null"
        }

    // 本车交互终端不能完整接收聚合数千字节的校验行；每个事务保持为短命令。
    private fun filesMatch(): Boolean =
        checks.all { command ->
            execute(
                "if $command; then echo AS_CLUSTER_FILE_MATCH; else echo AS_CLUSTER_FILE_MISMATCH; fi",
            ).lineSequence().any { it.trim() == "AS_CLUSTER_FILE_MATCH" }
        }

    /** 版本文件和逐项 cksum 都匹配才算已安装。命令失败由 [execute] 抛出。 */
    private fun installed(): Boolean {
        val matching =
            execute(
                "if [ -r $directory/version ] && [ \"\$(cat $directory/version)\" = '$version' ]; " +
                    "then echo AS_CLUSTER_VERSION_MATCH; else echo AS_CLUSTER_INSTALL_REQUIRED; fi",
            ).lineSequence().any { it.trim() == "AS_CLUSTER_VERSION_MATCH" }
        return matching && filesMatch()
    }

    /**
     * 查询安装与占用状态。未安装时返回 `not_installed`，不部署也不杀进程。
     */
    fun inspect(): JSONObject {
        if (!installed()) return JSONObject().put("state", "not_installed")
        val status = execute("sh $directory/g733_hook_runtime.sh status")
        return JSONObject().put(
            "state",
            if (status.lineSequence().any { it.trim() == "G733_RUNTIME_OWNED" }) "owned" else "restored",
        )
    }

    /**
     * 收回自有 Hook。固定清理不执行旧脚本，以便跨 APK 升级后仍能恢复原厂。
     *
     * 最多 15 次，每次在调用线程睡眠 1000 毫秒。确认唯一原厂进程后删除 `runtime.lock`。
     * 未确认时抛出 `cluster_restore_unconfirmed`。
     */
    fun stop(): JSONObject {
        repeat(15) { attempt ->
            val processes =
                execute(pids)
                    .lineSequence()
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it > 1 }
                    .toList()
            val libraries = processes.associateWith { execute("pidin -p $it libs 2>/dev/null || :") }
            val owned = libraries.filterValues { it.contains("$directory/libgalaxy_g733_cluster.so") }.keys
            if (processes.size == 1 && owned.isEmpty() && libraries.getValue(processes.single()).contains("PIE")) {
                execute("rm -rf $directory/runtime.lock; echo AS_CLUSTER_RESTORED")
                return JSONObject().put("state", "restored")
            }
            owned.forEach { pid ->
                execute("kill -${if (attempt < 2) "TERM" else "KILL"} $pid 2>/dev/null || :")
            }
            Thread.sleep(1000)
        }
        error("cluster_restore_unconfirmed")
    }

    /**
     * 保证指定代次的 Hook 处于运行状态。
     *
     * 租约失效时抛出 `cluster_lease_inactive`。已安装且连续健康检查通过则复用进程。
     * 需要部署时写入暂存目录；[close] 删除该目录。激活后租约失效会先 [stop] 再抛出。
     */
    fun ensure(generation: Long): JSONObject {
        check(leaseActive(generation)) { "cluster_lease_inactive" }
        if (installed()) {
            execute("sh $directory/g733_hook_runtime.sh inspect")
            // 连续观察现有进程；单次 NFS/心跳缺口不能触发原厂 HMI 重启。
            repeat(4) { attempt ->
                val live =
                    execute(
                        "if sh $directory/g733_hook_runtime.sh status | grep G733_RUNTIME_OWNED >/dev/null && " +
                            "$directory/health hook-health; then echo AS_CLUSTER_LIVE; " +
                            "else echo AS_CLUSTER_HEALTH_PENDING; fi",
                    )
                if (live.lineSequence().any { it.trim() == "AS_CLUSTER_LIVE" }) {
                    return JSONObject().put("state", "running").put("reused", true)
                }
                if (attempt < 3) Thread.sleep(1000)
            }
        }
        stop()
        check(leaseActive(generation)) { "cluster_lease_inactive" }
        if (!installed()) {
            val folder = File(stageRoot, "autoservice-cluster-${UUID.randomUUID()}")
            check(folder.mkdir()) { "cluster_stage_unavailable" }
            stage = folder
            files.forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }
            execute("umask 077; mkdir -p $directory")
            files.keys.forEach { name ->
                check(leaseActive(generation)) { "cluster_lease_inactive" }
                execute("cp /otaupdate/${folder.name}/$name $directory/$name")
            }
            execute("chmod 700 $directory/health")
            check(filesMatch()) { "cluster_deployed_file_mismatch" }
            execute("echo '$version' > $directory/version")
        }
        execute("sh $directory/g733_hook_runtime.sh inspect")
        check(leaseActive(generation)) { "cluster_lease_inactive" }
        val result = execute("sh $directory/g733_hook_runtime.sh activate --authorized-runtime")
        check(result.lineSequence().any { it.startsWith("G733_RUNTIME_UI_HEARTBEAT ") }) {
            "cluster_start_unconfirmed"
        }
        if (!leaseActive(generation)) {
            stop()
            error("cluster_lease_inactive")
        }
        return JSONObject().put("state", "running").put("reused", false)
    }

    /** 删除本次暂存目录。未创建或已经删除时不做任何事。 */
    override fun close() {
        stage?.let { folder ->
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
        stage = null
    }
}
