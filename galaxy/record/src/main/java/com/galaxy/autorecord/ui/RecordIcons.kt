package com.galaxy.autorecord.ui

import android.content.Context
import android.graphics.drawable.Drawable
import com.galaxy.autorecord.R

/** 审定稿普通控件使用 Lucide；车辆指示灯使用独立资源。来源与许可见 assets/licenses。 */
internal object RecordIcons {
    fun drawable(context: Context, kind: String, color: Int): Drawable {
        val id = when (kind) {
            "live" -> R.drawable.record_icon_live
            "library" -> R.drawable.record_icon_library
            "settings" -> R.drawable.record_icon_settings
            "back" -> R.drawable.record_icon_back
            "chevron" -> R.drawable.record_icon_chevron
            "expand" -> R.drawable.record_icon_expand
            "collapse" -> R.drawable.record_icon_collapse
            "storage" -> R.drawable.record_icon_storage
            "encode" -> R.drawable.record_icon_encode
            "record" -> R.drawable.record_icon_record
            "vehicle" -> R.drawable.record_icon_vehicle
            "playback" -> R.drawable.record_icon_playback
            "diagnose" -> R.drawable.record_icon_diagnose
            "about" -> R.drawable.record_icon_about
            "refresh" -> R.drawable.record_icon_refresh
            "play" -> R.drawable.record_icon_play
            "pause" -> R.drawable.record_icon_pause
            "previous" -> R.drawable.record_icon_previous
            "next" -> R.drawable.record_icon_next
            "filter" -> R.drawable.record_icon_filter
            "sliders" -> R.drawable.record_icon_sliders
            "event_previous" -> R.drawable.record_icon_event_previous
            "event_next" -> R.drawable.record_icon_event_next
            "more" -> R.drawable.record_icon_more
            "lock" -> R.drawable.record_icon_lock
            "left_turn" -> R.drawable.record_icon_left_turn
            "right_turn" -> R.drawable.record_icon_right_turn
            "steering" -> R.drawable.record_icon_steering
            "cruise" -> R.drawable.record_icon_cruise
            "low_beam" -> R.drawable.record_icon_low_beam
            "high_beam" -> R.drawable.record_icon_high_beam
            "auto_high_beam" -> R.drawable.record_icon_auto_high_beam
            "position" -> R.drawable.record_icon_position
            "fog" -> R.drawable.record_icon_fog
            "hold" -> R.drawable.record_icon_hold
            "parking" -> R.drawable.record_icon_parking
            "belt" -> R.drawable.record_icon_belt
            "brake" -> R.drawable.record_pedal_brake
            "accelerator" -> R.drawable.record_pedal_accelerator
            else -> R.drawable.record_icon_about
        }
        return context.getDrawable(id)!!.mutate().apply { setTint(color) }
    }
}
