package com.shilapi.xcertplay

import android.widget.Switch
import com.galaxy.auto.ui.GalaxySwitchStyle

/** 兼容入口：轨道/滑块与回填规则只在 public UI 维护一份。 */
internal object L7SwitchStyle {
    /** 应用公共开关样式。 */
    fun apply(control: Switch) = GalaxySwitchStyle.apply(control)
}
