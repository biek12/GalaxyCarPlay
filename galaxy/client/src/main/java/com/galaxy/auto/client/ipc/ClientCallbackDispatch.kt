package com.galaxy.auto.client.ipc

import android.os.Bundle
import android.os.Handler
import android.os.Parcel
import android.view.Surface
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoCallback

/**
 * 入站回调与两种Surface的所有者。
 *
 * borrowed只由main持有并释放，previewSurface只由io持有独立Parcel复制引用；两者不共用缓存。
 * 每个入站回调都捕获握手时的ticket，不改成每次读取当前代次后总能通过。onReady不在这里发出，
 * 仍由[ClientConnectionSession]在握手完成后回main执行。
 *
 * 业务回调一律用xxxSink命名：AIDL的override方法与回调同名时，若直接写onVehicleFeed(values)
 * 会被解析成override方法自身并无限向Handler投递消息，原实现用this@AutoClient限定正是为此。
 */
class ClientCallbackDispatch(
    private val connection: ClientConnectionSession,
    private val role: String,
    private val main: Handler,
    private val io: Handler,
    private val classLoader: ClassLoader?,
    private val vehicleFeedOnWorker: Boolean,
    private val surfaceSink: (Surface?) -> Unit,
    private val snapshotSink: (Bundle) -> Unit,
    private val stateSink: (String) -> Unit,
    private val commandSink: (Int) -> Unit,
    private val configuredSurfaceSink: (Surface?, Long) -> Unit,
    private val vehicleFeedSink: (Bundle) -> Unit,
) {
    /** main独有的入站Surface接收引用。 */
    private var borrowed: Surface? = null

    /** io独有的Parcel复制引用；即使预览归档也保留这条兼容API的资源处理。 */
    private var previewSurface: Surface? = null

    /** 按本代ticket构造IAutoCallback；线程与守卫顺序逐项沿用原实现。 */
    fun callbackFor(ticket: Int): IAutoCallback =
        object : IAutoCallback.Stub() {
            override fun onSnapshot(snapshot: Bundle) {
                main.post {
                    if (!connection.isClosed && connection.currentGeneration == ticket) {
                        snapshot.classLoader = classLoader
                        if (snapshot.containsKey("command")) {
                            commandSink(snapshot.getInt("command"))
                        } else {
                            snapshotSink(snapshot)
                        }
                    }
                }
            }

            override fun onSurface(
                kind: String,
                surface: Surface,
            ) {
                main.post {
                    if (connection.isClosed || connection.currentGeneration != ticket) {
                        surface.release()
                        return@post
                    }
                    val old = borrowed
                    borrowed = surface
                    surfaceSink(surface)
                    configuredSurfaceSink(surface, kind.substringAfter('@', "0").toLongOrNull() ?: 0L)
                    old?.release()
                }
            }

            override fun onProjectionState(
                kind: String,
                state: String,
            ) {
                main.post {
                    if (!connection.isClosed && connection.currentGeneration == ticket) {
                        if (state == "surface_closed") {
                            surfaceSink(null)
                            configuredSurfaceSink(null, 0L)
                            borrowed?.release()
                            borrowed = null
                        }
                        stateSink(state)
                    }
                }
            }

            override fun onVehicleFeed(values: Bundle) {
                (if (vehicleFeedOnWorker) io else main).post {
                    if (!connection.isClosed && connection.currentGeneration == ticket) {
                        values.classLoader = classLoader
                        vehicleFeedSink(values)
                    }
                }
            }
        }

    /**
     * lost/close的借用Surface释放次序：先两个回调，再release，最后configured(null,0)。
     * 与surface_closed分支次序不同，不能合并成统一clearAll。
     */
    fun releaseBorrowed() {
        surfaceSink(null)
        borrowed?.release()
        borrowed = null
        configuredSurfaceSink(null, 0L)
    }

    /** 同步参数检查、清空预览场景、Parcel复制与io投递失败释放，全部按原顺序执行。 */
    fun setPreviewSurface(
        value: Surface?,
        clearScene: () -> Unit,
    ) {
        require(role == AutoContract.PREVIEW)
        if (connection.isClosed) return
        if (value == null) clearScene()
        val copy = value?.let { duplicateSurface(it) }
        if (
            !io.post {
                if (connection.isClosed) {
                    copy?.release()
                    return@post
                }
                previewSurface?.release()
                previewSurface = copy
                connection.call { it.setPreviewSurface(connection.currentOwner, copy) }
            }
        ) {
            copy?.release()
        }
    }

    /** 只在握手的io线程读取io自有引用，供PREVIEW角色重连补发；不复制或转移所有权。 */
    fun previewSurfaceForReplay(): Surface? = previewSurface

    /** close最后一步在io上释放预览引用；已排队的Surface任务仍有机会走closed释放分支。 */
    fun releasePreview() {
        previewSurface?.release()
        previewSurface = null
    }

    /** 原Parcel复制：独立引用，finally回收Parcel，不新增Surface缓存。 */
    private fun duplicateSurface(value: Surface): Surface {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Surface.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
