package com.ecarx.autoservice.platform

/**
 * POSIX cksum：在 CRC 之后附加长度字节，区别于普通 CRC32；现场 QNX/Android 已有 cksum。
 *
 * 算法从归档的 `QnxReceiverDeployer` 原字节抽出（旧整屏投屏已退役），供
 * [QnxClusterHook] 的 health 材料逐项校验继续使用；本批不改变算法与字节语义。
 */
internal object PosixChecksum {
    /**
     * 计算 [bytes] 的 POSIX cksum 值。
     *
     * CRC 初值 0，逐字节按 0x04c11db7 多项式更新；随后按小端顺序把长度的每个
     * 非零字节也送进 CRC，最后取反并截到 32 位。空输入只更新长度 0（无字节），
     * 与系统 `cksum` 对拍由 `PlatformExecutionTest` 覆盖。
     */
    fun calculate(bytes: ByteArray): Long {
        var crc = 0L

        /** 单字节更新：高位异或后左移 8 次，多项式命中时异或常量。 */
        fun update(value: Int) {
            crc = crc xor ((value.toLong() and 255) shl 24)
            repeat(8) {
                crc =
                    if (crc and 0x80000000L != 0L) {
                        ((crc shl 1) xor 0x04c11db7L) and 0xffffffffL
                    } else {
                        (crc shl 1) and 0xffffffffL
                    }
            }
        }

        bytes.forEach { update(it.toInt()) }
        var length = bytes.size
        while (length != 0) {
            update(length and 255)
            length = length ushr 8
        }
        return crc.inv() and 0xffffffffL
    }
}
