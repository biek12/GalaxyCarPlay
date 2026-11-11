package com.shilapi.xcertplay

import android.content.Context
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** 原生文字角色与 UI 组件规范 0.2 保持一致；行高随系统字体缩放。 */
internal object L7Typography {
    enum class Role(val shared: com.galaxy.auto.ui.GalaxyTypography.Role) {
        PAGE_TITLE(com.galaxy.auto.ui.GalaxyTypography.Role.PAGE_TITLE),
        SECTION_TITLE(com.galaxy.auto.ui.GalaxyTypography.Role.SECTION_TITLE),
        LABEL(com.galaxy.auto.ui.GalaxyTypography.Role.LABEL),
        VALUE(com.galaxy.auto.ui.GalaxyTypography.Role.VALUE),
        DESCRIPTION(com.galaxy.auto.ui.GalaxyTypography.Role.DESCRIPTION),
        FEEDBACK(com.galaxy.auto.ui.GalaxyTypography.Role.FEEDBACK);
        val size get()=shared.size
        val line get()=shared.line
        val medium get()=shared.medium
        val secondary get()=shared.secondary
    }

    fun text(context: Context, value: String, role: Role) = TextView(context).apply {
        text = value
        com.galaxy.auto.ui.GalaxyTypography.apply(this, role.size, role.line, role.medium)
        L7Ui.text(this, if (role.secondary) R.color.product_ui_muted else R.color.product_ui_text)
    }
}
