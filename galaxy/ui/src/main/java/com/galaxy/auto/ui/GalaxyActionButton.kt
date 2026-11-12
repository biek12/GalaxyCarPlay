package com.galaxy.auto.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.Button

/**
 * 统一按钮：XML 与代码创建共用同一实例类型，主/次、compact 与宿主装饰都走同一份样式。
 *
 * [actionIcon] 由宿主（例如 CarPlay 图标策略）显式赋值，公共层不根据文案推断图标。
 * [setEnabled] 只在实际状态变化时重新绑定样式，避免轮询刷新产生连续的无障碍事件。
 */
class GalaxyActionButton
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = android.R.attr.buttonStyle,
    ) : Button(context, attrs, defStyleAttr) {
        /** 宿主显式指定的按钮图标；为空时由宿主装饰器自行决定是否绘制。 */
        var actionIcon: Int? = null

        /** 可用状态变化后重解析颜色与背景；同值重复设置不重绘。 */
        override fun setEnabled(enabled: Boolean) {
            val changed = isEnabled != enabled
            super.setEnabled(enabled)
            if (changed) GalaxyStyle.refresh(this)
        }
    }
