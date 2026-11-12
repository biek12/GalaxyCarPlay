package com.ecarx.autoservice.projection

/** 单生产者/单消费者的3缓冲邮箱：一份发送、一份待发、一份采集；新帧替换待发旧帧。 */
internal class GalaxyHabFrames(private val size: Int = GalaxyHabLayout.WIDTH * GalaxyHabLayout.HEIGHT * 4) {
    data class Frame(val pixels: ByteArray, val acquiredAtNanos: Long)
    private val free = ArrayDeque<ByteArray>()
    private var allocated = 0
    private var pending: Frame? = null
    private var closed = false
    private var replaced = 0L
    @Synchronized fun acquire(): ByteArray? = when {
        closed -> null
        free.isNotEmpty() -> free.removeFirst()
        allocated < 3 -> ByteArray(size).also { ++allocated }
        else -> null
    }
    @Synchronized fun publish(bytes: ByteArray, acquiredAtNanos: Long = 0) {
        if (closed) return
        pending?.let { free.addLast(it.pixels); ++replaced }
        pending = Frame(bytes, acquiredAtNanos)
    }
    @Synchronized fun take(): ByteArray? = takeStamped()?.pixels
    @Synchronized fun takeStamped(): Frame? = pending.also { pending = null }
    @Synchronized fun release(bytes: ByteArray) { if (!closed) free.addLast(bytes) }
    @Synchronized fun metrics() = "allocated=$allocated,free=${free.size},pending=${pending != null},replaced=$replaced"
    @Synchronized fun close() { closed = true; pending = null; free.clear() }
}
