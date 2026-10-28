package com.shilapi.xcertplay.steering

/** 迁自星枢SteeringControls；只保留手机互联动作，不迁RULE/SCRIPT或全局按键占用。 */
enum class SteeringEdge { PRESS, RELEASE, CANCEL }
enum class SteeringGesture { PRESS, RELEASE, CLICK, DOUBLE_CLICK, LONG_PRESS }
enum class SteeringConnection { STOPPED, CONNECTING, REGISTERED, OBSERVING, FAILED }
enum class SteeringAction { PLAY, PAUSE, PREVIOUS, NEXT, SIRI, ANSWER_CALL, END_CALL }
data class SteeringKeyEvent(val code: Int, val edge: SteeringEdge, val elapsedMs: Long)
data class SteeringGestureEvent(val code: Int, val gesture: SteeringGesture, val heldMs: Long = 0)
data class SteeringBinding(val keyCode: Int, val gesture: SteeringGesture, val action: SteeringAction)
data class SteeringConfiguration(val bindings: List<SteeringBinding> = emptyList(),
    val clickWindowMs: Long = 400, val longPressMs: Long = 800) {
    fun validated(): SteeringConfiguration {
        require(clickWindowMs in 100..1_000 && longPressMs in 300..3_000 && longPressMs > clickWindowMs)
        require(bindings.size <= 64)
        require(bindings.all { it.keyCode > 0 && it.gesture in MAPPABLE_GESTURES })
        require(bindings.map { it.keyCode to it.gesture }.distinct().size == bindings.size)
        return this
    }
    companion object {
        val MAPPABLE_GESTURES = listOf(SteeringGesture.CLICK, SteeringGesture.DOUBLE_CLICK, SteeringGesture.LONG_PRESS)
    }
}
data class SteeringSnapshot(val enabled: Boolean = true,
    val connection: SteeringConnection = SteeringConnection.STOPPED, val detail: String = "",
    val learning: Boolean = false, val learnedCode: Int? = null,
    val recent: List<SteeringKeyEvent> = emptyList(), val lastAction: SteeringAction? = null,
    val lastAccepted: Boolean? = null, val configuration: SteeringConfiguration = SteeringConfiguration())

/** 原始回调输入；实现只观察，不请求原车按键的全局消费权。 */
interface SteeringTransport {
    fun subscribe(event: (SteeringKeyEvent) -> Unit,
                  status: (SteeringConnection, String) -> Unit): java.io.Closeable
}

/** 星枢原识别顺序：单击等双击窗口，长按只触发一次，CANCEL清该键。 */
class SteeringGestureDecoder(private val clickMs: Long, private val longMs: Long) {
    private data class Press(val at: Long, var longSent: Boolean = false)
    private data class Click(val until: Long)
    private val presses = mutableMapOf<Int, Press>()
    private val clicks = mutableMapOf<Int, Click>()
    fun reset() { presses.clear(); clicks.clear() }
    fun accept(event: SteeringKeyEvent): List<SteeringGestureEvent> = buildList {
        if (event.edge == SteeringEdge.CANCEL) { presses.remove(event.code); clicks.remove(event.code) }
        addAll(flush(event.elapsedMs))
        when (event.edge) {
            SteeringEdge.PRESS -> if (event.code !in presses) {
                presses[event.code] = Press(event.elapsedMs)
                add(SteeringGestureEvent(event.code, SteeringGesture.PRESS))
            }
            SteeringEdge.RELEASE -> {
                val press = presses.remove(event.code) ?: return@buildList
                val held = (event.elapsedMs - press.at).coerceAtLeast(0)
                add(SteeringGestureEvent(event.code, SteeringGesture.RELEASE, held))
                if (press.longSent || held >= longMs) {
                    clicks.remove(event.code)
                    if (!press.longSent) add(SteeringGestureEvent(event.code, SteeringGesture.LONG_PRESS, held))
                } else {
                    val click = clicks[event.code]
                    if (click != null && event.elapsedMs <= click.until) {
                        clicks.remove(event.code)
                        add(SteeringGestureEvent(event.code, SteeringGesture.DOUBLE_CLICK, held))
                    } else clicks[event.code] = Click(event.elapsedMs + clickMs)
                }
            }
            SteeringEdge.CANCEL -> { presses.remove(event.code); clicks.remove(event.code) }
        }
    }
    fun flush(now: Long): List<SteeringGestureEvent> = buildList {
        presses.forEach { (code, press) ->
            if (!press.longSent && now - press.at >= longMs) {
                press.longSent = true
                clicks.remove(code)
                add(SteeringGestureEvent(code, SteeringGesture.LONG_PRESS, now - press.at))
            }
        }
        clicks.toMap().forEach { (code, click) ->
            if (now > click.until && code !in presses) {
                clicks.remove(code)
                add(SteeringGestureEvent(code, SteeringGesture.CLICK))
            }
        }
    }
}
