package com.ecarx.autoservice.projection

/**
 * 一次启动按同一HAB协议依次尝试App进程、现存UID0 helper、必要的接收端准备。
 * 成功的连接就是送帧连接，不另开探测会话；失败必须释放后才允许下一owner。
 */
internal class GalaxyHabSessionStart<T>(
    private val connect: (Boolean) -> T,
    private val current: () -> Boolean,
) {
    fun existing(): T? {
        for (root in listOf(false, true)) {
            if (!current()) return null
            try { return connect(root) } catch (error: GalaxyHabOwnerBusy) { throw error } catch (_: Exception) { /* 仅清理完成的尝试可继续。 */ }
        }
        return null
    }
}

internal class GalaxyHabOwnerBusy : IllegalStateException("hab_sender_reap_failed")
