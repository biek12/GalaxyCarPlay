package com.ecarx.autoservice.platform.foundation

import android.os.Bundle
import com.ecarx.autoservice.ServiceTrace
import com.ecarx.autoservice.ipc.ClientRegistry
import com.galaxy.auto.contract.IAutoResult
import org.json.JSONObject

/**
 * 客户端权限请求在排队并核对租约之后的授权与回读。
 *
 * 仍只允许 [ClientPermissionPolicy] 里的记录仪包和权限。缺什么才准备 Root，
 * OVERLAY 走原来的 appops，其他走 pm grant。回读失败不放宽，迟到租约不回结果。
 */
internal class ClientPermissionRequests(
    private val postShell: (Runnable) -> Unit,
    private val postMain: (Runnable) -> Unit,
    private val postCallback: (Runnable) -> Unit,
    private val executeShell: (String, Int, String) -> JSONObject,
    private val ensureRoot: (Boolean) -> String,
    private val closed: () -> Boolean,
    private val paused: () -> Boolean,
    private val automatic: () -> Boolean,
    private val rootEnabled: () -> Boolean,
    private val overlayAllowed: (Int, String) -> Boolean,
    private val permissionGranted: (String, Int) -> Boolean,
    private val leaseAlive: (ClientRegistry.Lease) -> Boolean,
) {
    /**
     * 对 [requested] 做授权和回读。
     *
     * [calling] 决定用户号，目标包仍是记录仪。已有权限不调用 [ensureRoot]。
     * 平台已关、Root 关闭、授权命令失败或回读失败都写入原来的 error，granted 为 false。
     */
    fun request(
        lease: ClientRegistry.Lease,
        calling: Int,
        requested: List<String>,
        result: IAutoResult,
    ) {
        val policy = ClientPermissionPolicy
        val target = policy.RECORD_PACKAGE
        val user = calling / 100000
        postShell(
            Runnable {
                val response = Bundle()
                try {
                    check(!closed() && !paused() && automatic()) { "platform_stopped" }

                    fun granted(permission: String): Boolean =
                        if (permission == policy.OVERLAY) {
                            overlayAllowed(calling, target)
                        } else {
                            permissionGranted(permission, calling)
                        }

                    val missing = requested.filter { !granted(it) }
                    if (missing.isNotEmpty()) {
                        check(rootEnabled()) { "root_disabled" }
                        ensureRoot(false)
                        missing.forEach { permission ->
                            val command =
                                if (permission == policy.OVERLAY) {
                                    "cmd appops set --user $user $target SYSTEM_ALERT_WINDOW allow"
                                } else {
                                    "pm grant --user $user $target $permission"
                                }
                            val value = executeShell(command, 10_000, "ROOT")
                            check(
                                value.optInt("exitCode", -1) == 0 &&
                                    !value.optBoolean("timedOut") &&
                                    !value.has("error"),
                            ) {
                                "permission_grant_failed"
                            }
                        }
                    }
                    check(requested.all { granted(it) }) { "permission_readback_failed" }
                    response.putBoolean("granted", true)
                } catch (error: Exception) {
                    ServiceTrace.event("client_permission_failed", error)
                    response.putBoolean("granted", false)
                    response.putString("error", error.message ?: error.javaClass.simpleName)
                }
                postMain(
                    Runnable {
                        if (!closed() && leaseAlive(lease)) {
                            postCallback(Runnable { runCatching { result.onResult(response) } })
                        }
                    },
                )
            },
        )
    }
}
