package com.galaxy.autocluster.runtime

import android.content.Context
import android.content.res.Configuration

/** App与仪表沿用同一日夜选择；只覆盖本App资源，不修改车机系统设置。 */
internal object ClusterAppearance {
    fun wrap(base: Context): Context {
        val mode=base.getSharedPreferences("cluster-display",0).getString("appearance","auto")
        if(mode=="auto")return base
        val configuration=Configuration(base.resources.configuration)
        configuration.uiMode=(configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if(mode=="night")Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return base.createConfigurationContext(configuration)
    }
}
