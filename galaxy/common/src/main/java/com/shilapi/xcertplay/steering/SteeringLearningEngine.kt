package com.shilapi.xcertplay.steering

/** 星枢学习状态顺序的纯逻辑移植；工作线程串行调用，持久化成功后才确认配置。 */
class SteeringLearningEngine(
    configuration: SteeringConfiguration = SteeringConfiguration(),
    private val elapsed: () -> Long,
    private val dispatch: (SteeringAction) -> Boolean,
) {
    var snapshot = SteeringSnapshot(configuration = configuration.validated())
        private set
    private var decoder = SteeringGestureDecoder(configuration.clickWindowMs, configuration.longPressMs)
    private val physicalDown = mutableSetOf<Int>()
    private val suppressed = mutableSetOf<Int>()
    private var learningCode: Int? = null
    private var learningUntil = 0L
    private var blockedUntil = 0L
    private var closed = false

    fun connection(connection: SteeringConnection, detail: String) {
        if (closed) return
        if (connection in setOf(SteeringConnection.CONNECTING, SteeringConnection.FAILED, SteeringConnection.STOPPED)) {
            cancelLearning()
            decoder.reset()
            physicalDown.clear()
            suppressed.clear()
            snapshot = snapshot.copy(recent = emptyList(), learnedCode = null)
        }
        snapshot = snapshot.copy(connection = connection, detail = detail)
    }

    fun setEnabled(enabled: Boolean) {
        if (closed) return
        if (!enabled) cancelLearning()
        decoder.reset()
        suppressed += physicalDown
        snapshot = snapshot.copy(enabled = enabled)
    }

    fun learn(): Boolean {
        if (closed || !snapshot.enabled || snapshot.connection !in setOf(SteeringConnection.REGISTERED, SteeringConnection.OBSERVING)) return false
        suppressed += physicalDown
        decoder.reset()
        learningCode = null
        learningUntil = elapsed() + LEARNING_MILLIS
        snapshot = snapshot.copy(learning = true, learnedCode = null)
        return true
    }

    fun cancelLearning() {
        learningCode?.let(suppressed::add)
        if (snapshot.learning) blockedUntil = elapsed() + snapshot.configuration.clickWindowMs
        learningCode = null
        learningUntil = 0
        decoder.reset()
        snapshot = snapshot.copy(learning = false, learnedCode = null)
    }

    fun proposed(gesture: SteeringGesture, action: SteeringAction): SteeringConfiguration {
        check(!closed && !snapshot.learning)
        val code = checkNotNull(snapshot.learnedCode) { "尚未观察到完整按下/松开事件" }
        require(gesture in SteeringConfiguration.MAPPABLE_GESTURES)
        return snapshot.configuration.copy(bindings = snapshot.configuration.bindings
            .filterNot { it.keyCode == code && it.gesture == gesture } + SteeringBinding(code, gesture, action)).validated()
    }

    fun configure(configuration: SteeringConfiguration) {
        if (closed) return
        val next = configuration.validated()
        decoder = SteeringGestureDecoder(next.clickWindowMs, next.longPressMs)
        suppressed += physicalDown
        snapshot = snapshot.copy(configuration = next, learnedCode = null)
    }

    fun receive(event: SteeringKeyEvent) {
        if (closed || event.code <= 0 || snapshot.connection !in setOf(SteeringConnection.REGISTERED, SteeringConnection.OBSERVING)) return
        if (snapshot.learning && elapsed() >= learningUntil) cancelLearning()
        if (event.edge == SteeringEdge.PRESS && event.code !in physicalDown && physicalDown.size >= 64) {
            connection(SteeringConnection.FAILED, "方控未释放按键数已达上限，请重新开启监听")
            return
        }
        val wasDown = event.code in physicalDown || event.code in suppressed
        if (event.edge == SteeringEdge.PRESS) physicalDown += event.code else physicalDown -= event.code
        snapshot = snapshot.copy(connection = SteeringConnection.OBSERVING,
            recent = (listOf(event) + snapshot.recent).take(RECENT_LIMIT))
        if (snapshot.learning) {
            if (event.edge == SteeringEdge.PRESS) suppressed += event.code
            if (event.edge == SteeringEdge.PRESS && !wasDown && learningCode == null) learningCode = event.code
            if (event.code == learningCode && event.edge != SteeringEdge.PRESS) {
                learningCode = null
                learningUntil = 0
                blockedUntil = elapsed() + snapshot.configuration.clickWindowMs
                snapshot = snapshot.copy(learning = false,
                    learnedCode = event.code.takeIf { event.edge == SteeringEdge.RELEASE })
            }
            if (event.edge != SteeringEdge.PRESS) suppressed.remove(event.code)
            return
        }
        if (event.code in suppressed) {
            if (event.edge != SteeringEdge.PRESS) suppressed.remove(event.code)
            return
        }
        if (!snapshot.enabled) return
        fire(decoder.accept(event))
    }

    /** 手机会话更替时清掉旧单击/长按；已按住的键需先松开才可触发新会话。 */
    fun sessionChanged() {
        if (closed) return
        decoder.reset()
        suppressed += physicalDown
    }

    fun tick() {
        if (closed) return
        if (snapshot.learning && elapsed() >= learningUntil) cancelLearning()
        if (!snapshot.learning && snapshot.enabled) fire(decoder.flush(elapsed()))
    }

    private fun fire(events: List<SteeringGestureEvent>) {
        if (closed || snapshot.learning || !snapshot.enabled || elapsed() < blockedUntil) return
        events.forEach { event ->
            snapshot.configuration.bindings.firstOrNull { it.keyCode == event.code && it.gesture == event.gesture }?.let { binding ->
                val accepted = runCatching { dispatch(binding.action) }.getOrDefault(false)
                snapshot = snapshot.copy(lastAction = binding.action, lastAccepted = accepted)
            }
        }
    }

    fun close() {
        if (closed) return
        cancelLearning()
        closed = true
        physicalDown.clear()
        suppressed.clear()
        snapshot = snapshot.copy(connection = SteeringConnection.STOPPED, recent = emptyList(), detail = "")
    }

    companion object {
        const val LEARNING_MILLIS = 15_000L
        const val RECENT_LIMIT = 12
    }
}
