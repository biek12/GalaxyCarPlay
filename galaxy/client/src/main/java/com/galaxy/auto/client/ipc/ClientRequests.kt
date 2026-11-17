package com.galaxy.auto.client.ipc

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.ParcelFileDescriptor
import com.galaxy.auto.client.diagnostics.DiagnosticEvents
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoResult
import java.io.File

/**
 * 一次性请求与结果路由：临时文件、只读FD、票据与各自错误形状都按原方法保留。
 *
 * 不保存第二份连接、缓存或订阅。ticket类结果在调用时捕获代次，page/value/getValues
 * 原本只有closed守卫，不擅自补generation过滤；有独立try的请求保留自己的回执，
 * 不用统一call静默或统一错误Bundle改写语义。
 */
class ClientRequests(
    private val connection: ClientConnectionSession,
    private val app: Context,
    private val main: Handler,
    private val io: Handler,
    private val classLoader: ClassLoader?,
) {
    /** createTempFile按原边界留在try之外；结果和异常都只在未关闭且本代次时回main。 */
    fun prepareOemArtwork(
        bytes: ByteArray,
        result: (Bundle) -> Unit,
    ) {
        require(bytes.size <= 2 * 1024 * 1024)
        val ticket = connection.currentGeneration
        io.post {
            val file = File.createTempFile("oem-artwork-", ".bin", app.cacheDir)
            try {
                file.writeBytes(bytes)
                val remote = connection.currentApi ?: error("service_unavailable")
                val callback =
                    object : IAutoResult.Stub() {
                        override fun onResult(value: Bundle) {
                            main.post {
                                if (!connection.isClosed && connection.currentGeneration == ticket) result(value)
                            }
                        }
                    }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    remote.prepareOemArtwork(connection.currentOwner, it, callback)
                }
            } catch (error: Exception) {
                main.post {
                    if (!connection.isClosed && connection.currentGeneration == ticket) {
                        result(Bundle().apply { putString("error", error.javaClass.simpleName) })
                    }
                }
            } finally {
                file.delete()
            }
        }
    }

    /** 调用时捕获ticket；不新增同步传输、命令校验或正文日志。 */
    fun executeShell(
        transport: String,
        command: String,
        timeoutMillis: Int,
        result: (Bundle) -> Unit,
    ) {
        val ticket = connection.currentGeneration
        io.post {
            connection.call { remote ->
                remote.executeShell(
                    connection.currentOwner,
                    transport,
                    command,
                    timeoutMillis,
                    object : IAutoResult.Stub() {
                        override fun onResult(value: Bundle) {
                            main.post {
                                if (!connection.isClosed && connection.currentGeneration == ticket) result(value)
                            }
                        }
                    },
                )
            }
        }
    }

    /** 权限回调与租约代次绑定；api为空也走原异常回执与client_permission诊断，不扩权限。 */
    fun requestClientPermissions(
        permissions: List<String>,
        result: (Bundle) -> Unit,
    ) {
        require(permissions.isNotEmpty() && permissions.size <= 8)
        val ticket = connection.currentGeneration
        io.post {
            fun deliver(value: Bundle) {
                main.post {
                    if (!connection.isClosed && connection.currentGeneration == ticket) result(value)
                }
            }
            try {
                val remote = connection.currentApi ?: error("service_unavailable")
                remote.requestClientPermissions(
                    connection.currentOwner,
                    permissions.toTypedArray(),
                    object : IAutoResult.Stub() {
                        override fun onResult(value: Bundle) = deliver(value)
                    },
                )
            } catch (error: Exception) {
                DiagnosticEvents.error(
                    "client_permission",
                    error,
                    mapOf(
                        "caller" to app.packageName,
                        "method" to "requestClientPermissions",
                        "count" to permissions.size,
                    ),
                )
                deliver(
                    Bundle().apply {
                        putBoolean("granted", false)
                        putString("error", error.javaClass.simpleName)
                    },
                )
            }
        }
    }

    /** 哨兵空闲只传powerSequence，没有缓存或重放。 */
    fun reportSentinelIdle(powerSequence: Long) {
        io.post { connection.call { it.reportSentinelIdle(connection.currentOwner, powerSequence) } }
    }

    /** 缺服务与异常回执形状为accepted=false加异常类名，不与权限/Web互相代验。 */
    fun configureSentinel(
        enabled: Boolean,
        result: (Bundle) -> Unit,
    ) {
        val ticket = connection.currentGeneration
        io.post {
            fun deliver(value: Bundle) {
                main.post {
                    if (!connection.isClosed && connection.currentGeneration == ticket) result(value)
                }
            }
            try {
                checkNotNull(connection.currentApi) { "service_unavailable" }
                    .configureSentinel(
                        connection.currentOwner,
                        enabled,
                        object : IAutoResult.Stub() {
                            override fun onResult(value: Bundle) = deliver(value)
                        },
                    )
            } catch (error: Exception) {
                deliver(
                    Bundle().apply {
                        putBoolean("accepted", false)
                        putString("error", error.javaClass.simpleName)
                    },
                )
            }
        }
    }

    /** 文件属于调用方，只打开本次FD并关闭；异常固定中文错误，不删除录像文件。 */
    fun vehicleWebUpload(
        clipId: String,
        direction: String,
        file: File,
        result: (Bundle) -> Unit,
    ) {
        val ticket = connection.currentGeneration
        io.post {
            fun deliver(value: Bundle) {
                main.post {
                    if (!connection.isClosed && connection.currentGeneration == ticket) result(value)
                }
            }
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    checkNotNull(connection.currentApi).vehicleWebUpload(
                        connection.currentOwner,
                        clipId,
                        direction,
                        fd,
                        file.length(),
                        object : IAutoResult.Stub() {
                            override fun onResult(value: Bundle) = deliver(value)
                        },
                    )
                }
            } catch (error: Exception) {
                deliver(Bundle().apply { putString("error", "媒体传输失败") })
            }
        }
    }

    /** action/payload原样传递；异常回执保留error.message或service_unavailable。 */
    fun vehicleWebRequest(
        action: String,
        payload: Bundle,
        result: (Bundle) -> Unit,
    ) {
        val ticket = connection.currentGeneration
        io.post {
            fun deliver(value: Bundle) {
                main.post {
                    if (!connection.isClosed && connection.currentGeneration == ticket) result(value)
                }
            }
            try {
                checkNotNull(connection.currentApi) { "service_unavailable" }
                    .vehicleWebRequest(
                        connection.currentOwner,
                        action,
                        payload,
                        object : IAutoResult.Stub() {
                            override fun onResult(value: Bundle) = deliver(value)
                        },
                    )
            } catch (error: Exception) {
                deliver(Bundle().apply { putString("error", error.message ?: "service_unavailable") })
            }
        }
    }

    /** 分页固定limit32，catalog决定原API；结果只判closed，原来没有generation守卫。 */
    fun vehiclePage(
        offset: Int,
        catalog: Boolean,
        result: (Bundle) -> Unit,
    ) {
        io.post {
            connection.call { remote ->
                val page =
                    if (catalog) {
                        remote.getVehicleCatalog(offset, 32)
                    } else {
                        remote.getVehicleProperties(offset, 32)
                    }
                main.post { if (!connection.isClosed) result(page) }
            }
        }
    }

    /** 单值原样传propertyId/areaId/offset；结果只判closed。 */
    fun vehicleValue(
        propertyId: Int,
        areaId: Int,
        offset: Int,
        result: (Bundle) -> Unit,
    ) {
        io.post {
            connection.call { remote ->
                val value = remote.getVehicleValue(propertyId, areaId, offset)
                main.post { if (!connection.isClosed) result(value) }
            }
        }
    }

    /** 不在客户端新增命令白名单，服务端校验保持。 */
    fun mediaCommand(command: Int) {
        io.post { connection.call { it.mediaCommand(connection.currentOwner, command) } }
    }

    /** 只读服务端已采集的缓存，不触发车机读取；结果仅closed守卫并设classLoader。 */
    fun getVehicleValues(
        keys: Collection<String>,
        result: (Bundle) -> Unit,
    ) {
        val next = keys.distinct().sorted()
        require(AutoContract.feedKeysValid(next.toTypedArray())) { "invalid_feed_keys" }
        io.post {
            connection.call { remote ->
                val values = remote.getVehicleValues(next.toTypedArray())
                main.post {
                    if (!connection.isClosed) {
                        values.classLoader = classLoader
                        result(values)
                    }
                }
            }
        }
    }

    /** 诊断扫描的服务端本UID限制保持，不把诊断扫描自动开启。 */
    fun setVehicleScan(enabled: Boolean) {
        io.post { connection.call { it.setVehicleScan(enabled) } }
    }
}
