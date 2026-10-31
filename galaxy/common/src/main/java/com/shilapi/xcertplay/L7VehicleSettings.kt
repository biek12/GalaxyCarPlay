package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import android.widget.Toast
import com.shilapi.xcertplay.host.R

/** 车型选择独立于音频参数；沿用确认与下次连接应用契约。 */
internal object L7VehicleSettings {
    fun page(context: Context, parent: LinearLayout, profileChanged: (() -> Unit)? = null) {
        val model = GalaxyVehicleAudioProfiles.model(context)
        val modelNames = GalaxyVehicleAudioProfiles.Model.entries.map { GalaxyVehicleModelConfirmation.name(context, it) }
        fun text(id: Int) = context.getString(id)
        fun refresh() { parent.removeAllViews(); page(context, parent, profileChanged) }
        L7SettingsSection.add(parent, description = text(R.string.l7_vehicle_settings_note)) { card ->
            card.addView(L7Components.valueRow(context, text(R.string.l7_template_model), modelNames[model.ordinal]) {
                L7Components.select(context, text(R.string.l7_template_model), modelNames, model.ordinal,
                    text(R.string.l7_save_next_connection)) { selected ->
                    change(context) {
                        GalaxyVehicleAudioProfiles.selectModel(context, GalaxyVehicleAudioProfiles.Model.entries[selected])
                        GalaxyVehicleModelConfirmation.markReviewed(context)
                        refresh()
                    }
                }
            })
            card.addView(L7SettingRow(context, text(R.string.l7_vehicle_profile),
                text(if (model == GalaxyVehicleAudioProfiles.Model.CUSTOM) R.string.l7_vehicle_custom_note
                    else R.string.l7_vehicle_preset_note)).apply {
                setValue(text(L7AudioSettings.modeName(GalaxyVehicleAudioProfiles.mode(context))))
            })
            card.addView(L7Components.switchRow(context, text(R.string.galaxy_vehicle_speed_iphone),
                text(R.string.galaxy_vehicle_speed_iphone_note),
                GalaxyVehicleOutputSettings.vehicleSpeedToIphone(context)) { enabled ->
                // 成功时不重建页面：开关此时已是目标状态，且不能在监听器派发期间销毁它自己的视图。
                if (!change(context) { GalaxyVehicleOutputSettings.setVehicleSpeedToIphone(context, enabled) }) refresh()
            })
            val detected = GalaxyVehicleModelDetector.detect()
            card.addView(L7Components.valueRow(context, text(R.string.l7_template_detect),
                detected?.let { GalaxyVehicleModelConfirmation.name(context, it) } ?: text(R.string.l7_template_detect_unknown)) {
                if (detected == null) Toast.makeText(context,
                    R.string.l7_template_detect_unknown, Toast.LENGTH_LONG).show()
                else GalaxyVehicleModelConfirmation.confirm(context, detected, ::refresh)
            })
        }
    }

    /** 返回是否保存成功；失败时提示且由调用方决定是否重建，避免在开关回调里销毁正在派发的视图。 */
    private fun change(context: Context, action: () -> Unit): Boolean {
        val saved = runCatching(action).isSuccess
        if (!saved)
            Toast.makeText(context, R.string.l7_template_save_failed, Toast.LENGTH_LONG).show()
        return saved
    }
}
