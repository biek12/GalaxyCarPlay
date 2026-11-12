package com.ecarx.autoservice.platform.adb

import java.io.IOException

/**
 * 把 [ControlFailure] 带出连接线程的 IO 失败。
 *
 * 消息固定为原因名的小写。本异常不关闭套接字，由抛出点的所有者释放。
 */
internal class ControlTransportException(
    val reason: ControlFailure,
) : IOException(reason.name.lowercase())
