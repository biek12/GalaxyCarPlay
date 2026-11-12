package com.ecarx.autoservice.platform.hook

import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 从本 APK 读取 Hook 固定资产，并在调用方完成背景分支后计算版本。
 *
 * 不登录 QNX，也不提前连接远端。Zip 在返回或失败前关闭，调用方可以随后删除 APK 文件。
 */
internal object HookMaterials {
    private val clusterAssets =
        listOf(
            "libgalaxy_g733_cluster.so",
            "g733_hook_runtime.sh",
            "hmi.cksum",
            "libc.cksum",
            "background.cksum",
            "rightmenu.cksum",
            "hook.cksum",
            "build.json",
        )

    /**
     * 读取 8 项 cluster 资产，并把 `assets/qnx/receiver-candidate` 记为 health。
     *
     * 在调用线程打开 [apk]。cluster 资产长度必须在 1 到 2MiB；health 仍只要求条目存在。
     * 缺 cluster 资产抛出 `cluster_asset_missing`，缺接收端资产抛出 `receiver_asset_missing`。
     * 返回前关闭 Zip。
     */
    fun read(apk: String): Map<String, ByteArray> =
        ZipFile(apk).use { zip ->
            val cluster =
                clusterAssets.associateWith { name ->
                    val entry = zip.getEntry("assets/cluster/$name") ?: error("cluster_asset_missing")
                    require(entry.size in 1L..2L * 1024 * 1024)
                    zip.getInputStream(entry).use { it.readBytes() }
                }
            val healthEntry = zip.getEntry("assets/qnx/receiver-candidate") ?: error("receiver_asset_missing")
            val health = zip.getInputStream(healthEntry).use { it.readBytes() }
            cluster + ("health" to health)
        }

    /**
     * 按键排序后的值拼接 SHA-256。不读取文件，也不看键名。
     *
     * 调用方必须在背景材料写入之后调用，避免把未替换的包内背景提前算进版本。
     */
    fun version(files: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().values.forEach { digest.update(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
