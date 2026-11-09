package com.galaxy.autorecord.core

/**
 * 四向相机的**实测**能力基线（来源：docs/AutoRecord/四向相机远程实测-20261103.md）。
 *
 * 为什么要有这个对象：相机能力既不能按 dumpsys 推断，也不能按常见机型默认值假设。
 * 本车实测结论与直觉差异很大（只认 0–3、JPEG 仅 1280×800、上限 25fps、facing 恒 FRONT），
 * 这些数字必须只有一处来源，界面、预检、编码参数、容量估算都从这里取。
 *
 * 纪律：这里是**实测值**，不是能力承诺。没有实测过的项（长时间推流、热、原厂抢占）
 * 一律标注未验证，不得写成"支持"。
 */
object CameraProfile {

    /** 实测可用的 cameraId（52 个 ID 中只有这 4 个能真正出帧）。 */
    val USABLE_IDS: List<Int> = listOf(0, 1, 2, 3)

    /** 别名：51 与 2 是同一物理相机的两个 ID（画面 md5 不同但视角一致）。必须去重，不能当第五路。 */
    val ALIAS_IDS: Map<Int, Int> = mapOf(51 to 2)

    /** 实测失败区间：4–50 报 `IllegalArgumentException: connectHelper:1734: Illegal argument to HAL module`。 */
    val FAILING_IDS: IntRange = 4..50

    /** JPEG 唯一输出尺寸（`jpeg_sizes` 单值）。dumpsys 报的 3280×2464 不是本路径的输出。 */
    const val JPEG_WIDTH = 1280
    const val JPEG_HEIGHT = 800

    /** 实测帧率区间。**上限 25**：所以"30fps"选项在本车不可用，不能作为可选项展示为可用。 */
    val FPS_RANGE: IntRange = 1..25
    const val MAX_FPS = 25

    /** 返回缓冲区恒为 6 000 000 字节（与 `android.jpeg.maxSize` 同值），真实 JPEG 远小于它。 */
    const val BUFFER_BYTES = 6_000_000

    /** 典型真实 JPEG 大小区间（实测 0.55–0.83 MB），用于容量估算与实际落盘校验。 */
    const val TYPICAL_JPEG_MIN_BYTES = 550_000
    const val TYPICAL_JPEG_MAX_BYTES = 830_000

    /** 本机 SDK CameraMetadata 常量：0=LIMITED，2 才是 LEGACY；具体输出仍以实测为准。 */
    const val HARDWARE_LEVEL_LABEL = "LIMITED（输出与并发能力以实测为准）"

    /** `LENS_FACING` 全部为 FRONT、`SENSOR_ORIENTATION` 全为 0 ⇒ **不能用它们判方位**。 */
    const val DIRECTION_FROM_CHARACTERISTICS_RELIABLE = false

    /** 实测四路并发开流：并发发起、各自等待，四路均在 0.5 s 内出首帧。 */
    const val CONCURRENT_OPEN_SUPPORTED = true
    const val FIRST_FRAME_BUDGET_MS = 500L

    /** 方向是否已标定。实测轮次未能标定（两个候选假设互相冲突），默认按假设 A。 */
    const val DIRECTION_CALIBRATED = false

    /**
     * 在缓冲区里找 EOI 结束位置，返回**有效字节长度**（含 EOI 两字节）。
     *
     * 为什么必须这么做：相机每次都返回固定 6 MB 缓冲，后面是垃圾；直接落盘会写 6 MB 无用数据
     * （实测真实 JPEG 只有 0.55–0.83 MB）。
     *
     * 为什么不能"扫第一个 FFD9"：实测 JPEG 头是 `ffd8ffe1`，带 EXIF；EXIF 里常内嵌缩略图，
     * 缩略图自带一个 FFD9。naive 扫描会在缩略图处截断，得到一张坏图。
     * 所以这里按 JPEG 段结构走：逐段按长度跳（APP0/APP1/DQT/SOF/DHT…），
     * 进入 SOS 后再在熵编码数据里找 FFD9，并跳过 RSTn 重启标记。
     *
     * @param buffer 相机返回的原始缓冲
     * @param length 缓冲里有效数据的长度上限（通常是 buffer.size）
     * @return 有效长度；找不到 EOI 返回 -1（调用方必须当"坏帧"，不得按 6 MB 落盘）
     */
    fun jpegEndOffset(buffer: ByteArray, length: Int = buffer.size): Int {
        val limit = length.coerceAtMost(buffer.size)
        if (limit < 4) return -1
        fun u8(index: Int) = buffer[index].toInt() and 0xFF
        if (u8(0) != 0xFF || u8(1) != 0xD8) return -1

        var index = 2
        var inScanData = false
        while (index + 1 < limit) {
            if (!inScanData) {
                if (u8(index) != 0xFF) return -1
                val marker = u8(index + 1)
                when {
                    marker == 0xD9 -> return index + 2
                    // 无长度字段的标记：TEM(01)、RSTn(D0–D7)、SOI 重复(D8)
                    marker == 0x01 || marker in 0xD0..0xD8 -> index += 2
                    // SOS：之后是熵编码数据
                    marker == 0xDA -> {
                        if (index + 3 >= limit) return -1
                        val segLength = (u8(index + 2) shl 8) or u8(index + 3)
                        if (segLength < 2) return -1
                        index += 2 + segLength
                        inScanData = true
                    }
                    else -> {
                        if (index + 3 >= limit) return -1
                        val segLength = (u8(index + 2) shl 8) or u8(index + 3)
                        if (segLength < 2) return -1
                        index += 2 + segLength
                    }
                }
            } else {
                if (u8(index) == 0xFF) {
                    val next = u8(index + 1)
                    when {
                        next == 0xD9 -> return index + 2
                        next == 0x00 -> index += 2          // 字节填充（数据里的 0xFF 被写成 FF00）
                        next in 0xD0..0xD7 -> index += 2    // 重启标记
                        next == 0xFF -> index += 1          // 填充的 0xFF
                        else -> {
                            // 遇到下一个真实标记（如多扫描 progressive 的下一段）
                            inScanData = false
                        }
                    }
                } else {
                    index++
                }
            }
        }
        return -1
    }

    /** 校验缓冲是否是一个完整可解码的 JPEG（SOI 开头 + 有 EOI 结尾）。 */
    fun isCompleteJpeg(buffer: ByteArray, length: Int): Boolean {
        if (length < 4) return false
        val soi = (buffer[0].toInt() and 0xFF) == 0xFF && (buffer[1].toInt() and 0xFF) == 0xD8
        return soi && jpegEndOffset(buffer, length) > 0
    }

    /** 本次会话的相机能力摘要（诊断页一行可读）。 */
    fun capabilitySummary(): String =
        "JPEG ${JPEG_WIDTH}×${JPEG_HEIGHT} · ${FPS_RANGE.first}–${FPS_RANGE.last}fps · " +
            "并发 ${USABLE_IDS.size} 路 · 缓冲 ${BUFFER_BYTES / 1_000_000} MB 需按 EOI 截断"

    /** 未验证项（必须显式列出，避免被当成已支持）。 */
    fun unverifiedItems(): List<String> = listOf(
        "持续 25 fps 长时推流下的掉帧与温度",
        "与原厂 360/倒车影像的抢占共存",
        "MediaCodec 输入 Surface 的编码路径",
        "四路长时间并发的资源占用",
        "方向标定（当前为假设 A，未经地面真值验证）",
    )
}
