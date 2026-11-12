package com.shilapi.xcertplay

import android.content.Context
import com.galaxy.auto.ui.GalaxyViews
import com.shilapi.xcertplay.host.R
import com.galaxy.auto.ui.GalaxyTypography.Role as SharedRole

/**
 * 原生文字角色与 UI 组件规范 0.2 保持一致；行高随系统字体缩放。
 *
 * 角色数值只维护在 `GalaxyTypography`，本对象只保留宿主颜色别名绑定。
 */
internal object L7Typography {
    /** 兼容既有调用名的角色枚举；值直接指向公共角色。 */
    enum class Role(
        val shared: SharedRole,
    ) {
        PAGE_TITLE(SharedRole.PAGE_TITLE),
        SECTION_TITLE(SharedRole.SECTION_TITLE),
        LABEL(SharedRole.LABEL),
        VALUE(SharedRole.VALUE),
        DESCRIPTION(SharedRole.DESCRIPTION),
        FEEDBACK(SharedRole.FEEDBACK),
        ;

        val size get() = shared.size
        val line get() = shared.line
        val medium get() = shared.medium
        val secondary get() = shared.secondary
    }

    /** 创建指定角色的文字并绑定宿主色；文字创建本身委托公共实现。 */
    fun text(context: Context, value: String, role: Role) =
        GalaxyViews.text(context, value, role.shared).apply {
            L7Ui.text(this, if (role.secondary) R.color.product_ui_muted else R.color.product_ui_text)
        }
}
