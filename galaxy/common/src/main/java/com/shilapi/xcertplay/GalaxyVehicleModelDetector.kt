package com.shilapi.xcertplay

import android.os.Build

/** 只匹配用户确认的车型标识；冲突和未知保持手动选择，不保存固件原文。 */
internal object GalaxyVehicleModelDetector {
    fun detect(device: String = Build.DEVICE, product: String = Build.PRODUCT,
               model: String = Build.MODEL): GalaxyVehicleAudioProfiles.Model? {
        val values = listOf(device, product, model)
        val identifiers = mapOf(GalaxyVehicleAudioProfiles.Model.L7 to "g636", GalaxyVehicleAudioProfiles.Model.L6 to "g733")
        val found = identifiers.filter { (_, code) ->
            val token = Regex("(?i)(?:^|[^a-z0-9])$code(?:$|[^a-z0-9])")
            values.any { token.containsMatchIn(it) }
        }
        return found.keys.singleOrNull()
    }
}
