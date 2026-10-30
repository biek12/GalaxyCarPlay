package com.galaxy.autocluster.theme

/** 编辑会话状态；不持有View/Activity。copy必须生成快照，避免拖拽中的可变数组污染历史。
 * 一次手势无论采样多少次只占一个撤销项；取消恢复手势起点，未提交草稿不触及投屏状态。
 */
internal class EditHistory<T : Any>(initial: T, private val copy: (T) -> T, private val limit: Int = 40) {
    private var current = copy(initial)
    private var saved = copy(initial)
    private var gestureStart: T? = null
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    init { require(limit > 0) }

    fun value(): T = copy(current)
    fun dirty(): Boolean = current != saved
    fun canUndo(): Boolean = past.isNotEmpty() || gestureStart?.let { it != current } == true
    fun canRedo(): Boolean = future.isNotEmpty() && gestureStart == null
    fun markSaved(value: T = current) { check(gestureStart == null) { "edit_gesture_pending" }; saved = copy(value) }

    fun change(next: T) {
        if (current == next) return
        if (gestureStart == null) { remember(current); future.clear() }
        current = copy(next)
    }

    fun beginGesture() { check(gestureStart == null) { "edit_gesture_already_started" }; gestureStart = copy(current) }
    fun finishGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        if (start != current) { remember(start); future.clear() }
    }
    fun cancelGesture() { gestureStart?.let { current = copy(it) }; gestureStart = null }

    fun undo() {
        finishGesture()
        if (past.isEmpty()) return
        future.addLast(copy(current)); current = past.removeLast()
    }
    fun redo() {
        check(gestureStart == null) { "edit_gesture_pending" }
        if (future.isEmpty()) return
        remember(current); current = future.removeLast()
    }
    fun replace(next: T) {
        gestureStart = null; past.clear(); future.clear()
        current = copy(next); saved = copy(next)
    }

    private fun remember(value: T) {
        past.addLast(copy(value))
        if (past.size > limit) past.removeFirst()
    }
}
