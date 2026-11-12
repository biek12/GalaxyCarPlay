package com.ecarx.autoservice.ipc

import android.content.pm.PackageManager

/**
 * Binder 调用身份。
 *
 * 只接入调用 UID、本进程 UID、签名比对和包名查询这四个系统边界。
 * 不建立另一套权限框架，也不缓存 UID 或签名结果。
 * [requireUid] 与 [source] 都在调用它们的 Binder 线程上同步读取；
 * 包名必须在投递 main 之前取完，不能留到主线程任务里再读。
 */
internal class ServiceCallerIdentity(
    private val callingUid: () -> Int,
    private val processUid: () -> Int,
    private val checkSignatures: (Int, Int) -> Int,
    private val packagesForUid: (Int) -> Array<String>?,
) {
    /**
     * 读取一次调用 UID，并按原规则决定是否放行。
     *
     * 与本进程 UID 相同就直接返回，不调用签名检查。
     * 否则用本进程 UID 比对 [PackageManager.SIGNATURE_MATCH]；失败仍抛
     * [IllegalStateException]，消息保持 `client_signature_rejected`。
     * 诊断包名不参与这次判断，同签名的其他包继续放行。返回值是实际调用 UID。
     */
    fun requireUid(): Int {
        val uid = callingUid()
        check(
            uid == processUid() ||
                checkSignatures(uid, processUid()) == PackageManager.SIGNATURE_MATCH,
        ) {
            "client_signature_rejected"
        }
        return uid
    }

    /**
     * 生成失败报告用的诊断来源。
     *
     * 在当前线程读取调用 UID 和包名。null 当成空数组；只留下四个既有包，
     * 未知包被滤掉，所以结果可以是空串。保留传入数组的原顺序和重复项，再用逗号拼接。
     * 这个列表不是授权条件。
     */
    fun source(): String {
        val diagnosticPackages =
            setOf(
                "com.ecarx.autoservice",
                "com.ecarx.carplay",
                "com.galaxy.autorecord",
                "com.galaxy.autocluster",
            )
        return packagesForUid(callingUid())
            .orEmpty()
            .filter { it in diagnosticPackages }
            .joinToString(",")
    }
}
