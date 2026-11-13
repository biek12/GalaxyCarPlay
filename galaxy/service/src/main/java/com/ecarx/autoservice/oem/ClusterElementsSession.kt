package com.ecarx.autoservice.oem

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.ecarx.autoservice.ClusterBackgroundStore
import com.ecarx.autoservice.ServiceTrace
import com.ecarx.autoservice.ipc.ClientRegistry
import com.ecarx.autoservice.platform.root.RootServiceClient
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.contract.IAutoResult

/**
 * 原厂元素配置、背景图片和 Hook 的所有者。
 *
 * 代次、修订和配置状态只在这里更新。Hook 在需要且尚未创建时才建立。
 * 构造不创建 Hook，也不导入图片。
 */
internal class ClusterElementsSession(
    private val context: Context,
    private val main: Handler,
    private val postBackground: (Runnable) -> Unit,
    private val postCallback: (Runnable) -> Unit,
    private val clients: ClientRegistry,
    private val closed: () -> Boolean,
    private val onPublish: () -> Unit,
    private val hookFactory: (changed: () -> Unit) -> HookPort = { changed ->
        ControllerHook(
            ClusterHookController(
                context,
                changed,
                RootServiceClient::hookControl,
                RootServiceClient::hookReceipt,
            ),
        )
    },
) {
    /**
     * 具名 Hook 边界。
     *
     * 生产实现转给 [ClusterHookController]。测试只记录这些调用，不连接 QNX。
     */
    interface HookPort {
        /** 按当前意图、Root 和配置更新。 */
        fun update(
            active: Boolean,
            ready: Boolean,
            config: ClusterDisplayConfiguration?,
        )

        /** 音乐帧已经写出后的回执。 */
        fun published(
            generation: Long,
            flags: Int,
        )

        /** 当前运行快照。每次调用都读一次，不另做缓存。 */
        fun snapshot(): Bundle

        /** 关闭 Hook。不关闭借用的图片 worker。 */
        fun close()
    }

    /** 背景配置代次。相等配置不增加；新图片和元素停用会增加。 */
    var imageGeneration: Long = 0L
        private set

    /** 成功应用的元素修订。 */
    var revision: Long = 0L
        private set

    /** ready、pending 或 failed。 */
    var configurationStatus: String = "ready"
        private set

    private var hook: HookPort? = null

    /**
     * Binder setActive 的元素副作用。
     *
     * 停用 ELEMENTS 时增加图片代次并回到 ready，然后保存 clusterElementsEnabled。其他角色不改代次。
     */
    fun applyActive(
        lease: ClientRegistry.Lease,
        active: Boolean,
    ) {
        if (lease.role == AutoContract.ELEMENTS && !active) {
            ++imageGeneration
            configurationStatus = "ready"
        }
        if (lease.role == AutoContract.ELEMENTS) {
            context
                .getSharedPreferences("platform", 0)
                .edit()
                .putBoolean("clusterElementsEnabled", active)
                .apply()
        }
    }

    /**
     * 配置与当前租约相同则不动作。
     *
     * @return 是否改了代次、修订和租约，调用方据此发布。
     */
    fun configure(
        lease: ClientRegistry.Lease,
        safe: ClusterDisplayConfiguration,
    ): Boolean {
        check(lease.role == AutoContract.ELEMENTS)
        if (lease.elements == safe) {
            return false
        }
        ++imageGeneration
        ++revision
        configurationStatus = "ready"
        lease.elements = safe
        return true
    }

    /**
     * 把已经 dup 的图片交给 main。
     *
     * 投递失败、已关闭、租约不对或配置相同都关闭 dup。新图片先 pending 再在背景 worker 导入。
     */
    fun submitImage(
        owner: IBinder,
        calling: Int,
        safe: ClusterDisplayConfiguration,
        duplicate: ParcelFileDescriptor,
        result: IAutoResult,
    ) {
        if (!main.post { acceptImage(owner, calling, safe, duplicate, result) }) {
            duplicate.close()
        }
    }

    /**
     * 用快照里的元素需求更新 Hook，并补上运行时、配置和状态字段。
     *
     * @return 这次是否需要元素。调用方再用 rootReady 决定音乐输出。
     */
    fun applyHook(
        snapshot: Bundle,
        rootReady: Boolean,
    ): Boolean {
        val elementConfiguration = snapshot.getBundle("clusterElements")?.let { ClusterDisplayConfiguration.from(it) }
        val elementsWanted = snapshot.getBoolean("clusterElementsRequested") && !closed()
        if (elementsWanted && hook == null) {
            hook = hookFactory(onPublish)
        }
        hook?.update(elementsWanted, rootReady, elementConfiguration)
        snapshot.putBundle("clusterElementRuntime", hook?.snapshot())
        snapshot.putString("clusterConfigurationStatus", configurationStatus)
        snapshot.putString(
            "clusterElementStatus",
            when (configurationStatus) {
                "pending" -> "preparing"
                "failed" -> "failed"
                else -> hook?.snapshot()?.getString("state") ?: "idle"
            },
        )
        return elementsWanted
    }

    /** 把音乐帧回执交给当前 Hook。还没有 Hook 时不创建。 */
    fun published(
        generation: Long,
        flags: Int,
    ) {
        hook?.published(generation, flags)
    }

    /** 关闭 Hook。重复调用保持已关闭。 */
    fun closeHook() {
        hook?.close()
        hook = null
    }

    private fun acceptImage(
        owner: IBinder,
        calling: Int,
        safe: ClusterDisplayConfiguration,
        duplicate: ParcelFileDescriptor,
        result: IAutoResult,
    ) {
        if (closed()) {
            duplicate.close()
            return
        }
        val lease =
            runCatching {
                clients.require(owner, calling).also { check(it.role == AutoContract.ELEMENTS) }
            }.getOrNull()
        if (lease == null) {
            duplicate.close()
            return
        }
        if (lease.elements == safe) {
            duplicate.close()
            postCallback(
                Runnable {
                    runCatching { result.onResult(Bundle().apply { putBoolean("accepted", true) }) }
                },
            )
            return
        }
        val ticket = ++imageGeneration
        configurationStatus = "pending"
        onPublish()
        postBackground(
            Runnable {
                val outcome =
                    runCatching { ClusterBackgroundStore(context).import(safe.image, duplicate) }
                        .onFailure { ServiceTrace.event("cluster_background_import", it) }
                runCatching { duplicate.close() }
                main.post {
                    val accepted =
                        !closed() &&
                            ticket == imageGeneration &&
                            clients.role(AutoContract.ELEMENTS) === lease
                    if (accepted) {
                        configurationStatus = if (outcome.isSuccess) "ready" else "failed"
                        if (outcome.isSuccess) {
                            ++revision
                            lease.elements = safe
                        }
                        onPublish()
                    }
                    postCallback(
                        Runnable {
                            runCatching {
                                result.onResult(
                                    Bundle().apply {
                                        putBoolean("accepted", accepted && outcome.isSuccess)
                                        if (outcome.isFailure) {
                                            putString("error", "background_import_failed")
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
    }

    private class ControllerHook(
        private val controller: ClusterHookController,
    ) : HookPort {
        override fun update(
            active: Boolean,
            ready: Boolean,
            config: ClusterDisplayConfiguration?,
        ) = controller.update(active, ready, config)

        override fun published(
            generation: Long,
            flags: Int,
        ) = controller.published(generation, flags)

        override fun snapshot(): Bundle = controller.snapshot()

        override fun close() = controller.close()
    }
}
