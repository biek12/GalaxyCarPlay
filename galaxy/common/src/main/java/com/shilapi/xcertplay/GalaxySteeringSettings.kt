package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.steering.GalaxySteeringSessions
import com.shilapi.xcertplay.steering.SteeringAction
import com.shilapi.xcertplay.steering.SteeringBinding
import com.shilapi.xcertplay.steering.SteeringConfiguration
import com.shilapi.xcertplay.steering.SteeringConnection
import com.shilapi.xcertplay.steering.SteeringGesture
import com.shilapi.xcertplay.steering.SteeringLearningSession
import com.shilapi.xcertplay.steering.SteeringSnapshot
import java.io.Closeable

/**
 * 方控设置的唯一入口：上半部分做学习/确认/保存/删除与总开关，下半部分是实物键观察与标注。
 *
 * 边界：开关沿用产品既有键（见 SteeringConfigurationStore），关闭后既不执行已保存映射，
 * 也停止原车键回送；本页只观察与确认，不发送测试按键、不拦截原车按键。
 * 线程：全部在主线程构建与刷新；学习句柄由 [GalaxySteeringSessions.observe] 在主线程持有，
 * 离开页面由宿主调用 [close] 注销观察句柄（最后一个句柄释放后Binder订阅一起注销）。
 * 生命周期：[background]/[resume] 转发给内嵌的观察页，页面不可见时停止它自己的监听日志。
 */
internal class GalaxySteeringSettings(private val activity: Activity, parent: LinearLayout,
    private val onLogs: () -> Unit = {}) : Closeable {
    private var closed = false
    private var state = SteeringSnapshot()
    private var selectedGesture = SteeringGesture.CLICK
    private var selectedAction: SteeringAction? = null
    private var dialog: AlertDialog? = null
    private lateinit var session: SteeringLearningSession
    private lateinit var enabled: L7SettingRow
    private lateinit var status: L7SettingRow
    private lateinit var recent: L7SettingRow
    private lateinit var candidate: L7SettingRow
    private lateinit var action: L7SettingRow
    private lateinit var gesture: L7SettingRow
    private lateinit var learn: Button
    private lateinit var cancel: Button
    private lateinit var confirm: Button
    private val bindings = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var renderedBindings: List<SteeringBinding>? = null

    /** 实物键观察与标注：复用原调试页实现，作为本页下半部分而不是第二个入口。 */
    private var watch: L7SteeringDebugPage? = null

    init {
        L7SettingsSection.add(parent, text(R.string.galaxy_steering_learning_title),
            text(R.string.galaxy_steering_learning_boundary)) { card ->
            enabled = L7Components.switchRow(activity, text(R.string.galaxy_steering_listening),
                text(R.string.galaxy_steering_listening_hint), true) { value ->
                runCatching { session.setEnabled(value) }.onFailure { failed() }
            }.also(card::addView)
            status = L7SettingRow(activity, text(R.string.galaxy_steering_service)).also(card::addView)
            recent = L7SettingRow(activity, text(R.string.galaxy_steering_recent)).also(card::addView)
            candidate = L7SettingRow(activity, text(R.string.galaxy_steering_candidate)).also(card::addView)
            gesture = L7Components.valueRow(activity, text(R.string.galaxy_steering_gesture), gestureLabel(selectedGesture)) {
                if (!closed) dialog = L7Components.select(activity, text(R.string.galaxy_steering_gesture),
                    SteeringConfiguration.MAPPABLE_GESTURES.map(::gestureLabel),
                    SteeringConfiguration.MAPPABLE_GESTURES.indexOf(selectedGesture), text(R.string.save)) { index ->
                    if (!closed) { selectedGesture = SteeringConfiguration.MAPPABLE_GESTURES[index]; update(state) }
                }
            }.also(card::addView)
            action = L7Components.valueRow(activity, text(R.string.galaxy_steering_action), text(R.string.galaxy_steering_choose_action)) {
                if (!closed) dialog = L7Components.select(activity, text(R.string.galaxy_steering_action),
                    SteeringAction.entries.map(::actionLabel), selectedAction?.let(SteeringAction.entries::indexOf) ?: -1, text(R.string.save)) { index ->
                    if (!closed) { selectedAction = SteeringAction.entries[index]; update(state) }
                }
            }.also(card::addView)
        }
        L7SettingsSection.actions(parent, text(R.string.galaxy_steering_learning_hint)) { group ->
            learn = L7Components.actionButton(activity, text(R.string.galaxy_steering_learn), primary = true) {
                selectedAction = null
                if (!session.startLearning()) status.setFeedback(text(R.string.galaxy_steering_wait_registration), error = true)
            }.also(group::addView)
            cancel = L7Components.actionButton(activity, text(R.string.cancel)) { session.cancelLearning() }.also(group::addView)
            confirm = L7Components.actionButton(activity, text(R.string.galaxy_steering_confirm)) { confirmMapping() }.also(group::addView)
        }
        parent.addView(bindings)
        L7SettingsSection.add(parent, text(R.string.galaxy_steering_watch_title),
            text(R.string.galaxy_steering_watch_hint)) { }
        watch = L7SteeringDebugPage(activity, parent, onLogs)
        session = GalaxySteeringSessions.observe(activity, ::update)
    }

    /** 宿主定时刷新入口；内嵌观察页自带200ms节拍，这里只补一次即时刷新。 */
    fun update() {
        if (!closed) watch?.update()
    }

    fun background() {
        if (!closed) watch?.background()
    }

    fun resume() {
        if (!closed) watch?.resume()
    }

    private fun update(snapshot: SteeringSnapshot) {
        if (closed) return
        state = snapshot
        enabled.setSwitchChecked(snapshot.enabled)
        status.setValue(text(when (snapshot.connection) {
            SteeringConnection.STOPPED -> R.string.galaxy_steering_stopped
            SteeringConnection.CONNECTING -> R.string.galaxy_steering_connecting
            SteeringConnection.REGISTERED -> R.string.galaxy_steering_registered
            SteeringConnection.OBSERVING -> R.string.galaxy_steering_observing
            SteeringConnection.FAILED -> R.string.galaxy_steering_failed
        }))
        status.setFeedback(snapshot.detail, error = snapshot.connection == SteeringConnection.FAILED)
        recent.setValue(snapshot.recent.take(4).joinToString(" · ") { "${it.code}:${it.edge.name}" }
            .ifEmpty { text(R.string.galaxy_steering_no_input) })
        candidate.setValue(when {
            snapshot.learning -> text(R.string.galaxy_steering_learning_wait)
            snapshot.learnedCode != null -> activity.getString(R.string.galaxy_steering_key_code, snapshot.learnedCode)
            else -> text(R.string.galaxy_steering_no_candidate)
        })
        gesture.setValue(gestureLabel(selectedGesture))
        action.setValue(selectedAction?.let(::actionLabel) ?: text(R.string.galaxy_steering_choose_action))
        learn.isEnabled = snapshot.enabled && !snapshot.learning && snapshot.connection in
            setOf(SteeringConnection.REGISTERED, SteeringConnection.OBSERVING)
        cancel.visibility = if (snapshot.learning || snapshot.learnedCode != null) View.VISIBLE else View.GONE
        confirm.isEnabled = snapshot.learnedCode != null && !snapshot.learning && selectedAction != null
        if (renderedBindings != snapshot.configuration.bindings) renderBindings(snapshot.configuration.bindings)
    }

    private fun confirmMapping() {
        val expectedCode = state.learnedCode ?: return
        val selected = selectedAction ?: return
        val gesture = selectedGesture
        dialog = L7Dialogs.builder(activity).setTitle(R.string.galaxy_steering_confirm)
            .setMessage(activity.getString(R.string.galaxy_steering_mapping_summary, expectedCode,
                gestureLabel(gesture), actionLabel(selected)))
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save) { _, _ ->
                if (!closed && session.snapshot().learnedCode == expectedCode) {
                    runCatching { session.confirm(gesture, selected) }.onFailure { failed() }
                }
            }.show()
    }

    private fun renderBindings(values: List<SteeringBinding>) {
        renderedBindings = values.toList()
        bindings.removeAllViews()
        L7SettingsSection.add(bindings, text(R.string.galaxy_steering_mappings)) { card ->
            if (values.isEmpty()) card.addView(L7SettingRow(activity, text(R.string.galaxy_steering_no_mappings)))
            values.forEach { binding ->
                card.addView(L7Components.actionRow(activity,
                    activity.getString(R.string.galaxy_steering_mapping_summary, binding.keyCode,
                        gestureLabel(binding.gesture), actionLabel(binding.action)),
                    text(R.string.galaxy_steering_remove_hint)) {
                    dialog = L7Dialogs.builder(activity).setTitle(R.string.galaxy_steering_remove)
                        .setMessage(activity.getString(R.string.galaxy_steering_key_code, binding.keyCode))
                        .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.galaxy_steering_remove) { _, _ ->
                            if (!closed) runCatching { session.remove(binding) }.onFailure { failed() }
                        }.show()
                })
            }
        }
    }

    private fun failed() {
        if (!closed) status.setFeedback(text(R.string.galaxy_steering_save_failed), error = true)
    }
    private fun text(id: Int) = activity.getString(id)
    private fun gestureLabel(gesture: SteeringGesture) = text(when (gesture) {
        SteeringGesture.DOUBLE_CLICK -> R.string.galaxy_steering_double_click
        SteeringGesture.LONG_PRESS -> R.string.galaxy_steering_long_press
        else -> R.string.galaxy_steering_click
    })
    private fun actionLabel(action: SteeringAction) = text(when (action) {
        SteeringAction.PLAY -> R.string.galaxy_steering_play
        SteeringAction.PAUSE -> R.string.galaxy_steering_pause
        SteeringAction.PREVIOUS -> R.string.galaxy_steering_previous
        SteeringAction.NEXT -> R.string.galaxy_steering_next
        SteeringAction.SIRI -> R.string.galaxy_steering_siri
        SteeringAction.ANSWER_CALL -> R.string.galaxy_steering_answer
        SteeringAction.END_CALL -> R.string.galaxy_steering_end
    })

    override fun close() {
        if (closed) return
        closed = true
        dialog?.dismiss()
        dialog = null
        watch?.close()
        watch = null
        session.close()
    }
}