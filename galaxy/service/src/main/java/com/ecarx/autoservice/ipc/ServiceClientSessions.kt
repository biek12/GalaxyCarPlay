package com.ecarx.autoservice.ipc

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.view.Surface
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoCallback

/**
 * 客户端登记、解绑和已归档的场景/预览/策略回执。
 *
 * 借用同一份 [ClientRegistry]。死亡仍由注册表先投 main，再由外层 main 调用 [detach]，两段投递不合并。
 */
internal class ServiceClientSessions(
    private val clients: ClientRegistry,
    private val main: Handler,
    private val postCallback: (Runnable) -> Unit,
    private val closed: () -> Boolean,
    private val recalculateDemand: () -> Unit,
    private val clearRole: (String) -> Unit,
    private val onElementsActive: (ClientRegistry.Lease, Boolean) -> Unit,
    private val onPublish: () -> Unit,
) {
    /**
     * 登记租约，或对已归档角色只回执。
     *
     * THEME 和 PREVIEW 不登记租约。其他角色登记后由调用方发布。
     * @return 是否新登记了租约。
     */
    fun attach(
        owner: IBinder,
        calling: Int,
        role: String,
        callback: IAutoCallback,
    ): Boolean {
        if (role == AutoContract.THEME || role == AutoContract.PREVIEW) {
            postCallback(Runnable { runCatching { callback.onProjectionState(role, PROJECTION_ARCHIVED) } })
            return false
        }
        clients.attach(owner, calling, role, callback)
        return true
    }

    /**
     * 移除租约，收缩车辆订阅，并清掉对应媒体角色。
     *
     * 重复移除直接返回。调用方已经在 main。
     */
    fun detach(owner: IBinder) {
        val old = clients.remove(owner) ?: return
        recalculateDemand()
        clearRole(old.role)
        onPublish()
    }

    /**
     * 切换租约 active。
     *
     * THEME 和 PREVIEW 直接返回。ELEMENTS 的代次和持久开关交给元素所有者。
     * @return 是否需要发布。
     */
    fun setActive(
        owner: IBinder,
        calling: Int,
        active: Boolean,
    ): Boolean {
        val lease = clients.require(owner, calling)
        if (lease.role == AutoContract.THEME || lease.role == AutoContract.PREVIEW) {
            return false
        }
        lease.active = active
        onElementsActive(lease, active)
        return true
    }

    /**
     * 非 MAP 调用把命令快照回给当前 MAP。
     *
     * 命令只接受调用方已经校验过的 0、1、2。
     */
    fun mediaCommand(
        owner: IBinder,
        calling: Int,
        command: Int,
    ) {
        check(clients.require(owner, calling).role != AutoContract.MAP)
        clients.role(AutoContract.MAP)?.callback?.let { callback ->
            postCallback(
                Runnable { runCatching { callback.onSnapshot(Bundle().apply { putInt("command", command) }) } },
            )
        }
    }

    /** 旧场景配置只回归档态，不保存 scene。 */
    fun configureScene(
        owner: IBinder,
        calling: Int,
    ) {
        val lease = clients.require(owner, calling)
        postCallback(Runnable { runCatching { lease.callback.onProjectionState(lease.role, PROJECTION_ARCHIVED) } })
    }

    /**
     * 先释放 Surface，再看服务是否已关闭。
     *
     * 已关闭也必须释放。之后才核对租约并回归档态。任何路径都不创建预览输出。
     */
    fun setPreviewSurface(
        owner: IBinder,
        calling: Int,
        surface: Surface?,
    ) {
        main.post {
            surface?.release()
            if (closed()) {
                return@post
            }
            runCatching {
                val lease = clients.require(owner, calling)
                postCallback(
                    Runnable {
                        runCatching { lease.callback.onProjectionState(lease.role, PROJECTION_ARCHIVED) }
                    },
                )
            }
        }
    }

    /** 旧投屏策略只回归档态，不写偏好，也不改尺寸。 */
    fun setProjectionPolicy(
        owner: IBinder,
        calling: Int,
    ) {
        val lease = clients.require(owner, calling)
        postCallback(Runnable { runCatching { lease.callback.onProjectionState(lease.role, PROJECTION_ARCHIVED) } })
    }

    private companion object {
        /** 旧整屏覆盖投屏归档态。只在这里作为回执文本。 */
        const val PROJECTION_ARCHIVED = "projection_archived"
    }
}
