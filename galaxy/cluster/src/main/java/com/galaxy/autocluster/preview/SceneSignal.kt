package com.galaxy.autocluster.preview

/**
 * 单条车辆信号在快照边界上的读数。
 *
 * [text] 保留原始文本。[number] 只在文本能解析成有限浮点时存在，有效 0 与缺失因此分开。
 * [receivedAt] 与 [maxAge] 使用同一时间单位，默认最大年龄 2000 毫秒。本类型不持有线程。
 */
internal data class SceneSignal(
    val text: String,
    val number: Float?,
    val valid: Boolean,
    val receivedAt: Long,
    val maxAge: Long = 2000,
) {
    /**
     * 判断该读数在 [now] 是否仍可消费。
     *
     * [valid] 为假、[now] 早于 [receivedAt]，或年龄超出 `0..maxAge` 闭区间时返回 false。
     */
    fun fresh(now: Long): Boolean = valid && now - receivedAt in 0..maxAge
}
