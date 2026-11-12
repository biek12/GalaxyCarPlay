package com.ecarx.autoservice.platform.hook

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 原厂 Hook 的 32 字节运行租约。
 *
 * 不持有锁。生产路径由 [QnxClusterHook] 在自己的串行锁里调用。
 * 读文件失败时返回 false，不删除租约文件，也不把缺文件当成有效租约。
 */
internal class HookLease(
    private val leaseFile: File = File("/data/vendor/nfs/nfs_ota/autoservice-media", "hook.bin"),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * 判断 [generation] 的租约是否仍有效。
     *
     * 在调用线程读文件。小端魔数必须是 `0x314c4847`，flags 非 0，代次匹配，
     * 到期时间晚于第一次读到的当前时刻，且与第二次读到的当前时刻相差不超过 6000 毫秒。
     * 两次读时钟的顺序保持原样，短条件失败时不再读下一次。
     */
    fun active(generation: Long): Boolean =
        runCatching {
            val bytes = leaseFile.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            bytes.size == 32 &&
                buffer.getInt(0) == 0x314c4847 &&
                buffer.getInt(4) != 0 &&
                buffer.getLong(8) == generation &&
                buffer.getLong(16) > nowMillis() &&
                buffer.getLong(16) - nowMillis() <= 6000
        }.getOrDefault(false)
}
