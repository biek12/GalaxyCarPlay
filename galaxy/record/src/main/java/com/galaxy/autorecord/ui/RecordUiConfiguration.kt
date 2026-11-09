package com.galaxy.autorecord.ui

import android.content.Context
import android.content.res.Configuration
import com.galaxy.autorecord.core.*

/** 统一主界面与哨兵全屏页的280dpi、显式日夜及触控尺度。 */
object RecordUiConfiguration {
    fun from(context:Context):Configuration {
        val base=Configuration(context.resources.configuration)
        val ratio=base.densityDpi.toFloat()/280f
        val result=Configuration().apply{
            setLocales(base.locales);densityDpi=280
            screenWidthDp=(base.screenWidthDp*ratio).toInt()
            screenHeightDp=(base.screenHeightDp*ratio).toInt()
            smallestScreenWidthDp=(base.smallestScreenWidthDp*ratio).toInt()
        }
        when(PrefsSettingsStore(context).load().themeMode){
            RecordSettings.THEME_DARK->result.uiMode=(base.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            RecordSettings.THEME_LIGHT->result.uiMode=(base.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        return result
    }
}
