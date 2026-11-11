package com.galaxy.auto.ui

import android.widget.Switch

/**
 * Flyme 开关：显示文案/轨道拆分/最小宽度与高度由 `GalaxyUi.Switch` 样式提供，
 * 本对象只绑定轨道与滑块 drawable（refresh 需要重解析）。
 *
 * 整行触控与 Android 开关语义保留；外部 [GalaxySettingRow.setSwitchChecked] 回填不触发业务回调。
 */
object GalaxySwitchStyle {
    /** 应用公共开关样式；重复调用只重新解析资源，不改变选中状态。 */
    fun apply(control: Switch) {
        GalaxyStyle.bind(control) {
            control.background = null
            control.thumbTintList = null
            control.trackTintList = null
            control.thumbDrawable = control.context.getDrawable(R.drawable.galaxy_ui_switch_thumb)
            control.trackDrawable = control.context.getDrawable(R.drawable.galaxy_ui_switch_track)
        }
    }
}
