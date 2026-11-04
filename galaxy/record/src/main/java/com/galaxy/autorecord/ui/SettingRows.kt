package com.galaxy.autorecord.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * 设置行（80dp 基准）。
 *
 * 口径（样式基线 §4/§5）：
 * - 整行 80dp、左右 20dp、上下 10dp；标题 LABEL 20sp + 副标题 DESCRIPTION 16sp；
 * - 行内反馈 FEEDBACK 16sp 就地显示（错误用 danger），不弹 Toast 代替；
 * - 右侧当前值 VALUE 18sp muted + 箭头；只读行不装箭头，也不装开关。
 *
 * 高度由内容决定并受 80dp 下限约束：有副标题时正好 80dp，出现反馈行时自然增高，
 * 不截断错误原因（错误必须可读完整）。
 */
internal open class SettingRow(
    protected val ui: RecordUi,
    title: String,
    description: String = "",
    icon: String? = null,
) : LinearLayout(ui.context) {

    val labelView: android.widget.TextView = ui.text(title, RecordRole.LABEL)
    val descriptionView: android.widget.TextView = ui.text(description, RecordRole.DESCRIPTION).apply {
        visibility = if (description.isEmpty()) GONE else VISIBLE
    }
    val feedbackView: android.widget.TextView = ui.text("", RecordRole.FEEDBACK).apply {
        visibility = GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val valueView: android.widget.TextView = ui.text("", RecordRole.VALUE).apply {
        gravity = Gravity.END
        visibility = GONE
    }
    private val arrow: View = ui.glyph("chevron", ui.disabled, 4)
    private val trailing = ui.row().apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(valueView, LayoutParams(-2, -2))
        addView(arrow, LayoutParams(ui.dp(24), ui.dp(24)).apply { marginStart = ui.dp(4) })
        arrow.visibility = GONE
    }
    private val textColumn = ui.column()
    private var leadingControl: View? = null
    private val hasIcon = icon != null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.dp(80)
        setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10))
        if (icon != null) {
            addView(ui.glyph(icon, ui.textColor, 4), LayoutParams(ui.dp(32), ui.dp(32)).apply { marginEnd = ui.dp(16) })
        }
        textColumn.addView(labelView, LayoutParams(-1, -2))
        textColumn.addView(descriptionView, LayoutParams(-1, -2))
        textColumn.addView(feedbackView, LayoutParams(-1, -2))
        addView(textColumn, LayoutParams(0, -2, 1f))
        addView(trailing, LayoutParams(-2, -2))
    }

    fun setValue(value: String) {
        valueView.text = value
        valueView.visibility = if (value.isEmpty()) GONE else VISIBLE
    }

    fun setDescription(value: String) {
        descriptionView.text = value
        descriptionView.visibility = if (value.isEmpty()) GONE else VISIBLE
    }

    fun setFeedback(value: String, error: Boolean = false) {
        feedbackView.text = value
        feedbackView.setTextColor(if (error) ui.danger else ui.muted)
        feedbackView.visibility = if (value.isEmpty()) GONE else VISIBLE
    }

    fun showArrow(visible: Boolean) {
        arrow.visibility = if (visible) VISIBLE else GONE
    }

    /** 整行可点（valueRow）；水波只在行内扩散，不加整卡底色。 */
    fun clickableRow(description: String, onClick: () -> Unit): SettingRow {
        showArrow(true)
        contentDescription = description
        isClickable = true
        isFocusable = true
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(ui.rippleColor), null, null,
        )
        setOnClickListener { if (isEnabled) onClick() }
        return this
    }

    /** 禁用只减弱控件；标题、当前值和禁用原因保持可读（样式基线 §5）。 */
    fun setRowEnabled(enabled: Boolean) {
        isEnabled = enabled
        trailing.alpha = if (enabled) 1f else 0.4f
        leadingControl?.alpha = if (enabled) 1f else 0.4f
        leadingControl?.isEnabled = enabled
        isClickable = enabled && hasOnClickListeners()
    }

    /** 开关行把开关放在文字之前，命中区仍是整行 80dp。 */
    protected fun insertLeadingControl(control: View) {
        leadingControl = control
        addView(control, if (hasIcon) 1 else 0, LayoutParams(-2, -2).apply { marginEnd = ui.dp(16) })
    }
}

/** 开关行：整行可点、开关在文字前；服务/设置回填走 setCheckedSilently 避免回环。 */
internal class SwitchSettingRow(
    ui: RecordUi,
    title: String,
    description: String = "",
) : SettingRow(ui, title, description) {
    private val control = ui.switchView()
    private var silent = false

    var onToggle: ((Boolean) -> Unit)? = null

    init {
        insertLeadingControl(control)
        control.setOnCheckedChangeListener { _, checked ->
            if (!silent) onToggle?.invoke(checked)
        }
        isClickable = true
        isFocusable = true
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(ui.rippleColor), null, null,
        )
        setOnClickListener { if (isEnabled && control.isEnabled) control.isChecked = !control.isChecked }
    }

    fun setCheckedSilently(value: Boolean) {
        silent = true
        try {
            control.isChecked = value
        } finally {
            silent = false
        }
    }
}
