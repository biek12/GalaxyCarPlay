package com.ecarx.autoservice.platform

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 旧整屏覆盖投屏退役后的 op4/op6 帧拒绝器，由 `RootServiceMain` 的真实分发循环直接调用。
 *
 * 这不是第二套分发框架：只对已退役的 op4（部署/唤起 QNX 接收端）与 op6
 * （启动本地 HAB bridge 子进程）作答复并关闭连接；其他 operation 一律返回
 * `false`（未处理），由原 `when` 分发继续处理车辆、Shell、哨兵与原厂 Hook。
 *
 * 生产与标准用例调用同一个 [rejectIfArchived] 方法，测试只把连接关闭动作换成
 * 记录型边界替身，不另写 reject 模型、不替换 [RootWire] 的帧解析。
 */
internal object RootProjectionArchive {
    /** 归档回执阶段码；op4 的 `error` 与 `stage` 都用它，便于客户端按既有错误字段读取。 */
    const val STAGE = "legacy_projection_archived"

    /** 已退役的旧整屏投屏 operation：4=部署接收端，6=启动 HAB bridge。 */
    private const val OP_DEPLOY_RECEIVER = 4
    private const val OP_START_HAB_BRIDGE = 6

    /**
     * 若 [operation] 已退役，消费旧帧、回写归档回执并关闭连接，返回 `true`（调用方须结束本连接）。
     *
     * - op4：先按 [RootWire.read] 消费现有的长度前缀 JSON 帧（帧内容不参与任何决策），
     *   再按 [RootWire.write] 回 `error`/`stage=legacy_projection_archived`。
     * - op6：按旧协议回一个 `int -1` 并 flush，不读取 native 路径、不启动进程或线程。
     *
     * 线程/所有者：helper 的每连接 worker 线程；[closeConnection] 只关闭本次连接，
     * 不触碰 owner、认证材料或全局状态。非退役 operation 不写、不读、不关闭。
     *
     * @return true 表示本帧已由归档拒绝器处理且连接已关闭；false 表示应交回原分发。
     */
    fun rejectIfArchived(
        operation: Int,
        input: DataInputStream,
        output: DataOutputStream,
        closeConnection: () -> Unit,
    ): Boolean {
        when (operation) {
            OP_DEPLOY_RECEIVER -> {
                RootWire.read(input, 16384)
                RootWire.write(output, JSONObject().put("error", STAGE).put("stage", STAGE))
            }

            OP_START_HAB_BRIDGE -> {
                output.writeInt(-1)
                output.flush()
            }

            else -> return false
        }
        closeConnection()
        return true
    }
}
