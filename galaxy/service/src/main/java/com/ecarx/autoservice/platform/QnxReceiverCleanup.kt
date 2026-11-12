package com.ecarx.autoservice.platform

import org.json.JSONObject

/**
 * 旧整屏接收端退役后的显式清理：只保留 op5 的自有旧 receiver 清理能力。
 *
 * 旧整屏覆盖投屏已归档，本对象**不部署、不唤起、不 ensureRunning**，也不在
 * AutoService 启动/恢复时自动连接车端。只有 helper 收到显式的 op5 请求时，
 * 才按原命令做 PID/路径核验并有限 TERM 清理本 App 自己留下的旧接收程序。
 *
 * 现场清理是否完成由命令退出码与车端回执决定，本轮离线编码不连接、不清理车端。
 */
internal object QnxReceiverCleanup {
    private const val DIRECTORY = "/var/data/autoservice-hab"

    /** connection 只归清理职责：显式 op5 才建立一次带认证的短连接。 */
    private fun connection(request: JSONObject) =
        QnxTelnet("192.168.118.2").also {
            try {
                val login = QnxCredentials.login(request)
                it.login(login.username, login.password)
            } catch (error: Exception) {
                it.close()
                throw error
            }
        }

    /**
     * 清理自有旧 receiver：沿用归档 Deployer 的既有 PID/path 核验与有限 TERM 逻辑，
     * 不扩大为任意进程清理，也不改 HMI 或原车启动项。
     *
     * 数字 PID 校验 → `kill -0` 存活 → `pidin -p <pid> ar` 路径核验 → `kill -TERM`
     * → 等待 1s → 二次 `kill -0` → 删除 pid 文件；任一环节失败以退出码回报。
     */
    fun stop(request: JSONObject): JSONObject {
        connection(request).use { remote ->
            remote.execute(
                "if [ -f $DIRECTORY/receiver.pid ]; then p=\$(cat $DIRECTORY/receiver.pid); " +
                    "case \"\$p\" in ''|*[!0-9]*) exit 21;; esac; " +
                    "if kill -0 \"\$p\" 2>/dev/null; then " +
                    "pidin -p \"\$p\" ar | grep $DIRECTORY/receiver >/dev/null || exit 22; " +
                    "kill -TERM \"\$p\" || exit 23; sleep 1; " +
                    "kill -0 \"\$p\" 2>/dev/null && exit 24; fi; " +
                    "rm -f $DIRECTORY/receiver.pid; fi",
            )
        }
        return JSONObject().put("state", "receiver_stop_requested")
    }
}
