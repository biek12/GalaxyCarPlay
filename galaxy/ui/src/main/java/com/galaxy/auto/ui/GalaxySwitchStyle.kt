package com.galaxy.auto.ui

import android.widget.Switch

/**
 * Flyme 开关：轨道/滑块来自公共 drawable，按标题 20/32 的比例适配 92×48dp 视觉尺寸。
 *
 * 整行触控与 Android 开关语义保留；外部 [GalaxySettingRow.setSwitchChecked] 回填不触发业务回调。
 */
object GalaxySwitchStyle {
    /** 应用公共开关样式；重复调用只重新解析资源，不改变选中状态。 */
    fun apply(control: Switch) {
        control.showText = false
        control.splitTrack = false
        control.switchMinWidth = GalaxyViews.px(control.context, R.dimen.galaxy_ui_switch_min_width)
        control.minimumHeight = GalaxyViews.px(control.context, R.dimen.galaxy_ui_touch_target)
        GalaxyStyle.bind(control) {
            control.background = null
            control.thumbTintList = null
            control.trackTintList = null
            control.thumbDrawable = control.context.getDrawable(R.drawable.galaxy_ui_switch_thumb)
            control.trackDrawable = control.context.getDrawable(R.drawable.galaxy_ui_switch_track)
        }
    }
}
