package com.shilapi.xcertplay

import android.content.Context
import com.galaxy.auto.ui.GalaxyDialogs
import com.shilapi.xcertplay.host.R

/** 产品文案/图标与界面密度留在宿主，模态框实现只有公共UI一份。 */
internal object L7Dialogs {
    fun builder(context: Context) = GalaxyDialogs.Builder(context,
        custom = context.resources.getBoolean(R.bool.config_l7_product_ui),
        dialogContext = L7UiDensity.wrap(context),
        positiveLabel = { label -> if(context is GalaxyConfigurationContext && context.editable && label == R.string.l7_save_next_connection) R.string.profile_update_draft else label },
        titleIcon = { title -> L7Icons.dialog(context,title) }, decorate = L7Icons::decorate)
    fun refresh(context: Context) = GalaxyDialogs.refresh(context)
}
