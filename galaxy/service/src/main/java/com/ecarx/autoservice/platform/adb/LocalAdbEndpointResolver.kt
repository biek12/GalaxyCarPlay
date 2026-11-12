package com.ecarx.autoservice.platform.adb

import com.ecarx.autoservice.platform.root.ControlWork

/**
 * 按已保存的环回偏好解析 TLS ADB 端口。
 *
 * 在调用线程工作。不打开已认证会话；套接字由 [AdbTlsPortDiscovery] 在探测后释放。
 * 取消由 [ControlWork] 抛出，本对象不持有连接。
 */
internal class LocalAdbEndpointResolver(
    private val settings: AdbConnectionSettingsStore,
    private val onEvent: (String) -> Unit = {},
) : AdbEndpointResolver {
    /**
     * 解析当前端点。直连模式只探测配置端口；否则先用属性端口，再做有界发现。
     * 偏好不合法、端口不加密或发现失败时抛出 [ControlTransportException]。
     */
    override fun resolve(work: ControlWork): AdbEndpoint {
        val saved = settings.read()
        if (!saved.valid()) throw ControlTransportException(ControlFailure.INVALID_REQUEST)
        onEvent(
            "endpoint lookup started; host=${saved.host}; direct=${saved.directConnect}; " +
                "configured_port=${saved.port}",
        )
        if (saved.directConnect) {
            when (val probe = AdbTlsPortDiscovery.probe(saved.host, saved.port, work, DIRECT_PROBE_TIMEOUT_MS)) {
                AdbTlsProbe.SECURE ->
                    return AdbEndpoint(saved.host, saved.port).also {
                        onEvent("configured endpoint is secure TLS ADB; port=${saved.port}")
                    }
                AdbTlsProbe.NOT_SECURE -> {
                    onEvent("configured endpoint refused secure STLS; port=${saved.port}")
                    throw ControlTransportException(ControlFailure.SECURE_ADB_REQUIRED)
                }
                AdbTlsProbe.UNREACHABLE -> {
                    onEvent("configured endpoint unreachable; port=${saved.port}; result=$probe")
                    throw ControlTransportException(ControlFailure.DISCONNECTED)
                }
            }
        }
        AdbTlsPortDiscovery.activePort(saved.host, work)?.let {
            onEvent("TLS ADB property endpoint is reachable; port=$it")
            return AdbEndpoint(saved.host, it)
        }
        val port =
            AdbTlsPortDiscovery.discover(saved.host, DISCOVERY_TIMEOUT_MS, work)
                ?: throw ControlTransportException(ControlFailure.ADB_TLS_UNAVAILABLE)
        onEvent("TLS ADB endpoint discovered; port=$port")
        return AdbEndpoint(saved.host, port)
    }

    /**
     * 在恢复窗口内重新发现端口。发现失败时返回原端点，不抛出。
     * 取消仍由 [work] 抛出。
     */
    internal fun rediscover(
        endpoint: AdbEndpoint,
        work: ControlWork,
    ): AdbEndpoint {
        val port = AdbTlsPortDiscovery.discover(endpoint.host, RECOVERY_DISCOVERY_TIMEOUT_MS, work)
        return if (port == null) endpoint else endpoint.copy(port = port)
    }

    private companion object {
        const val DIRECT_PROBE_TIMEOUT_MS = 2_000
        const val DISCOVERY_TIMEOUT_MS = 12_000L
        const val RECOVERY_DISCOVERY_TIMEOUT_MS = 15_000L
    }
}
