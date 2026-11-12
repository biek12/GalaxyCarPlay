package com.ecarx.autoservice.platform.hook

import android.util.AtomicFile
import android.util.Base64
import com.ecarx.autoservice.ServiceTrace
import com.ecarx.autoservice.platform.KzbBackgroundPatch
import com.ecarx.autoservice.platform.qnx.QnxCredentials
import com.ecarx.autoservice.platform.qnx.QnxTelnet
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 自有 Hook 的固定操作入口。只允许 ensure、inspect、stop，不接受 Shell、路径或认证参数。
 *
 * [control] 在本对象的串行锁上执行。Telnet 只在同一次 use 中持有；失败时 use 关闭连接，
 * 远端暂存由 [QnxClusterRemote.close] 删除。
 */
internal object QnxClusterHook {
    private const val SHARED = "/data/vendor/nfs/nfs_ota/autoservice-media"

    /** 与远端部署目录相同的固定路径。命令字面量由 [QnxClusterRemote] 持有。 */
    @Suppress("unused")
    private const val DIR = "/var/data/autoservice-cluster"

    private val lock = Any()
    private val lease = HookLease()

    /**
     * 读取 64 字节回执。调用线程不进入 [control] 的锁。
     *
     * 读失败或长度不符时返回空回执，不把 NFS 的 EIO 写成 HMI 失败。
     */
    fun receipt(): JSONObject {
        // 现场 NFS 原子 rename 窗口会返回 EIO；读失败是缺少回执，不能冒充 HMI 失败。
        val bytes =
            runCatching {
                File(SHARED, "hook-status.bin").takeIf { it.isFile && it.length() == 64L }?.readBytes()
            }.onFailure { ServiceTrace.event("cluster_receipt_read", it) }.getOrNull() ?: ByteArray(0)
        return JSONObject().put("receipt", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    /**
     * 按固定顺序执行一次 Hook 控制。
     *
     * 调用线程持有 [lock] 直到 Telnet 关闭。顺序是：校验动作和 ensure 租约、读取 APK 固定资产、
     * 取得登录材料、创建 Telnet 并登录、原厂 KZB 背景分支、计算材料版本、交给 [QnxClusterRemote]。
     * 不提前远程取文件。材料读取不登录 QNX。
     */
    fun control(
        request: JSONObject,
        userId: Int,
    ): JSONObject =
        synchronized(lock) {
            val action = request.getString("action")
            require(action in setOf("ensure", "inspect", "stop"))
            val generation = request.optLong("generation")
            // 动作与 ensure 租约在读取材料和连接 QNX 之前完成。
            if (action == "ensure") check(lease.active(generation)) { "cluster_lease_inactive" }
            val apk = System.getenv("CLASSPATH")?.substringBefore(':') ?: error("apk_missing")
            val files = HookMaterials.read(apk)
            val login = QnxCredentials.login(request)
            QnxTelnet("192.168.118.2").use { remote ->
                remote.login(login.username, login.password)
                val deployed = files.toMutableMap()
                val image = request.optString("image")
                if (action == "ensure" && image.isNotEmpty()) {
                    require(
                        userId in 0..9999 &&
                            image.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,95}\\.(png|jpg|jpeg|webp)")),
                    )
                    val private =
                        File("/data/user/$userId/com.ecarx.autoservice/no_backup/cluster-backgrounds")
                    val original = File(private, "oem-background.kzb")
                    if (!original.isFile) {
                        val stage =
                            File("/data/vendor/nfs/nfs_ota", "autoservice-background-${UUID.randomUUID()}")
                        check(stage.mkdir()) { "cluster_stage_unavailable" }
                        try {
                            remote.execute(
                                "cp /apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/background.kzb " +
                                    "/otaupdate/${stage.name}/original.kzb",
                            )
                            check(original.parentFile!!.isDirectory || original.parentFile!!.mkdirs())
                            val captured = File(stage, "original.kzb").readBytes()
                            check(KzbBackgroundPatch.originalMatches(captured)) {
                                "cluster_background_material_mismatch"
                            }
                            val atomic = AtomicFile(original)
                            val stream = atomic.startWrite()
                            try {
                                stream.write(captured)
                                atomic.finishWrite(stream)
                            } catch (error: Exception) {
                                atomic.failWrite(stream)
                                throw error
                            }
                        } finally {
                            stage.listFiles()?.forEach { it.delete() }
                            stage.delete()
                        }
                    }
                    val png =
                        File(private, "$image.rgb.png")
                            .also {
                                check(it.isFile && it.length() in 45..KzbBackgroundPatch.PNG_BYTES.toLong()) {
                                    "cluster_background_png_missing"
                                }
                            }.readBytes()
                    val candidate = KzbBackgroundPatch.replace(original.readBytes(), png)
                    deployed["background.kzb"] = candidate
                    // 资源版本只由图片字节决定；运行租约换代不应重部署同一背景并重启 HMI。
                    deployed["background.proof"] = KzbBackgroundPatch.proof(candidate)
                    deployed["background.mode"] = "replace\n".toByteArray()
                } else {
                    deployed["background.mode"] = "observe\n".toByteArray()
                }
                val version = HookMaterials.version(deployed)
                QnxClusterRemote(remote::execute, deployed, version, lease::active).use { port ->
                    when (action) {
                        "ensure" -> port.ensure(generation)
                        "stop" -> port.stop()
                        else -> port.inspect()
                    }
                }
            }
        }
}
