package com.galaxy.auto.ui

import android.widget.Switch


/** 按标题 20/32 的比例适配 Flyme 原生 92×48 dp 轨道；整行触控与 Android 开关语义保留。 */
object GalaxySwitchStyle {
    fun apply(control: Switch) {
        control.showText = false
        control.splitTrack = false
        control.switchMinWidth = GalaxyViews.dp(control.context, 58)
        control.minimumHeight = GalaxyViews.dp(control.context, 64)
        GalaxyStyle.bind(control) {
            control.background = null
            control.thumbTintList = null
            control.trackTintList = null
            control.thumbDrawable = control.context.getDrawable(R.drawable.galaxy_ui_switch_thumb)
            control.trackDrawable = control.context.getDrawable(R.drawable.galaxy_ui_switch_track)
        }
    }
}
