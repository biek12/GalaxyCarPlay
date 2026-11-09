package com.galaxy.autorecord.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.galaxy.autorecord.core.VehicleKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 实时与历史共用的呈现层。只显示调用方的数据，不采集、不拥有相机或解码器。 */
internal class RecordPlayerPanel(private val ui: RecordUi) : LinearLayout(ui.context) {
    val picture = FrameLayout(context)
    private val values = linkedMapOf<VehicleKey, TextView>()
    private val dateView = ui.text("—", RecordRole.DESCRIPTION)
    private val timeView = ui.text("—", RecordRole.DESCRIPTION)
    private val lights = linkedMapOf<VehicleKey?, View>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        val telemetry = ui.row().apply { setPadding(ui.dp(8), ui.dp(9), ui.dp(8), ui.dp(9)) }
        val clock = ui.column()
        listOf(dateView, timeView).forEach {
            it.textSize = 12f; it.setTextColor(ui.overlayText); it.gravity = Gravity.CENTER
            it.isSingleLine = true
            clock.addView(it, LayoutParams(-1, -2))
        }
        telemetry.addView(clock, LayoutParams(ui.dp(88), -2))
        val keys = listOf(VehicleKey.SPEED, VehicleKey.GEAR, VehicleKey.RPM, VehicleKey.STEERING,
            VehicleKey.FUEL, VehicleKey.SOC, VehicleKey.FUEL_RANGE, VehicleKey.EV_RANGE, VehicleKey.ODOMETER,
            VehicleKey.INSIDE_TEMP, VehicleKey.OUTSIDE_TEMP)
        keys.forEach { key ->
            val item = ui.column()
            val value = ui.text("—", RecordRole.VALUE).apply {
                textSize = 12f; setTextColor(ui.overlayText); gravity = Gravity.CENTER; isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            values[key] = value
            item.addView(value, LayoutParams(-1, -2))
            item.addView(ui.text(key.label, RecordRole.DESCRIPTION).apply {
                textSize = 11f; setTextColor(ui.overlayText); gravity = Gravity.CENTER; isSingleLine = true
            }, LayoutParams(-1, -2))
            telemetry.addView(item, LayoutParams(0, -2, 1f))
        }
        addView(telemetry, LayoutParams(-1, ui.dp(64)))
        addView(picture, LayoutParams(-1, -2))
        val status = ui.row().apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), 0, ui.dp(8), 0) }
        listOf(VehicleKey.BRAKE to "brake", VehicleKey.ACCELERATOR to "accelerator",
            VehicleKey.LEFT_TURN to "left_turn", VehicleKey.RIGHT_TURN to "right_turn",
            null to "steering", VehicleKey.CRUISE_STATE to "cruise",
            VehicleKey.LOW_BEAM to "low_beam", VehicleKey.HIGH_BEAM to "high_beam",
            VehicleKey.AUTO_HIGH_BEAM to "auto_high_beam",
            VehicleKey.POSITION_LIGHT to "position", VehicleKey.REAR_FOG to "fog",
            VehicleKey.AUTO_HOLD to "hold", VehicleKey.GEAR to "parking").forEach { (key, icon) ->
            val view = ui.glyph(icon, ui.disabled, 7)
            view.contentDescription = "${key?.label ?: "方向盘（状态含义待核实）"}：—"
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            lights[key] = view
            status.addView(view, LayoutParams(0, ui.dp(44), 1f))
        }
        addView(status, LayoutParams(-1, ui.dp(44)))
    }

    /** 回看限定在分配的空间内展示完整四路，避免按宽度无限撑高而裁掉第二行。 */
    fun fitPictureToAvailableHeight() {
        picture.layoutParams = LayoutParams(-1, 0, 1f)
    }

    fun bind(raw: Map<String, String>, wallMillis: Long) {
        dateView.text = dateFormat.format(Date(wallMillis))
        timeView.text = timeFormat.format(Date(wallMillis))
        values.forEach { (key, view) ->
            val value = raw[key.key]?.takeUnless { it == "null" || it == "—" }
            // 厂商 Float 转字符串可能带十多位尾数；只在呈现层保留一位，原始历史不改写。
            val display = value?.toBigDecimalOrNull()?.setScale(1, java.math.RoundingMode.HALF_UP)
                ?.stripTrailingZeros()?.toPlainString() ?: value
            view.text = if (display == null) "—" else display + key.unit
            view.contentDescription = "${key.label}：${value ?: "—"}${if (value == null) "" else key.unit}"
        }
        lights.forEach { (key, view) ->
            val value = key?.let { raw[it.key] }
            val number = value?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val active = if (key == VehicleKey.ACCELERATOR || key == VehicleKey.BRAKE) {
                number != null && number in 0.0..100.0 && number > 0
            } else if (key == VehicleKey.GEAR) {
                value == "P"
            } else if (key == VehicleKey.CRUISE_STATE) {
                value == "active"
            } else value?.lowercase() in setOf("on", "true", "active") || number == 1.0
            (view as android.widget.ImageView).imageTintList = android.content.res.ColorStateList.valueOf(
                if (active) Color.rgb(70, 222, 84) else if (value == null) (ui.overlayText and 0x00ffffff) or 0x66000000 else ui.overlayText,
            )
            view.contentDescription = "${key?.label ?: "方向盘（状态含义待核实）"}：${value ?: "—"}"
        }
    }

    companion object {
        /** 两种输入只注入自己的渲染格，四向顺序、间距与空间分配共用。 */
        fun grid(ui: RecordUi, directions: List<String>, fillHeight: Boolean, cell: (String) -> View): View {
            val grid = ui.column()
            directions.chunked(if (directions.size == 1) 1 else 2).forEach { pair ->
                val row = ui.row()
                pair.forEach { direction -> row.addView(cell(direction), LayoutParams(0, if (fillHeight) -1 else -2, 1f)) }
                grid.addView(row, if (fillHeight) LayoutParams(-1, 0, 1f) else LayoutParams(-1, -2))
            }
            return grid
        }
    }
}
