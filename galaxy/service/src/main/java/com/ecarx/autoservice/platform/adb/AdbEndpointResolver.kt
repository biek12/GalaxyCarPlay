package com.ecarx.autoservice.platform.adb

import com.ecarx.autoservice.platform.root.ControlWork

/**
 * 为一次控制任务解析本机 ADB 端点。实现不得打开已认证会话。
 */
internal interface AdbEndpointResolver {
    /**
     * 在调用线程解析端点。取消由 [work] 抛出。
     * 失败使用 [ControlTransportException]，不关闭 [work]。
     */
    fun resolve(work: ControlWork): AdbEndpoint
}
