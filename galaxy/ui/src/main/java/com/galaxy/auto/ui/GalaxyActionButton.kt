package com.galaxy.auto.ui

import android.content.Context
import android.widget.Button

/** 统一按钮的可用状态刷新；图标、占位和文字布局由公共样式管理。 */
class GalaxyActionButton(context: Context) : Button(context) {
    var actionIcon: Int? = null

    override fun setEnabled(enabled: Boolean) {
        val changed = isEnabled != enabled
        super.setEnabled(enabled)
        // 相同状态不重绘，避免轮询产生持续的无障碍事件。
        if (changed) GalaxyStyle.refresh(this)
    }
}
