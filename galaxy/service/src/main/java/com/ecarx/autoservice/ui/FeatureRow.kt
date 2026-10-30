package com.ecarx.autoservice.ui

import android.widget.LinearLayout

/** 整行触控保留Switch语义；快照更新不反向触发用户操作。 */
internal class FeatureRow(ui: ServiceUi, private val title: String, description: String, icon: String, changed: (Boolean) -> Unit) : LinearLayout(ui.context) {
    private val control = ui.control()
    private val detail = ui.label(description, 14f, true)
    private var binding = false
    init {
        orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12)); minimumHeight = ui.dp(100)
        addView(ui.glyph(icon), LayoutParams(ui.dp(46), ui.dp(46)).apply { marginEnd = ui.dp(18) })
        val labels = ui.column()
        labels.addView(ui.label(title, 21f))
        labels.addView(detail, LayoutParams(-1, -2).apply { topMargin = ui.dp(7) })
        addView(labels, LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(16) })
        control.contentDescription = "$title，$description"
        addView(control, LayoutParams(ui.dp(64), ui.dp(60)))
        control.setOnCheckedChangeListener { _, enabled -> if (!binding) changed(enabled) }
        setOnClickListener { control.toggle() }; isFocusable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ui.accentSoft), null, ui.rounded(ui.surface))
    }
    fun bind(enabled: Boolean, description: String) {
        binding = true
        control.isChecked = enabled; detail.text = description; control.contentDescription = "$title，$description"
        binding = false
    }
}
