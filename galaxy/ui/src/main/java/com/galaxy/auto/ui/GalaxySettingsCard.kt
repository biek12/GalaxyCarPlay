package com.galaxy.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout


/** 仅相邻设置行之间绘制细分隔线，不把标题、提示或操作按钮误分成条目。 */
class GalaxySettingsCard(context: Context) : LinearLayout(context) {
    private val separator = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(-1, -2)
        clipToOutline = true
        GalaxyStyle.bind(this) { background = GalaxyStyle.rounded(context, context.getColor(R.color.galaxy_settings_card), 4).apply { setStroke(GalaxyViews.dp(context, 1).coerceAtLeast(1), context.getColor(R.color.galaxy_settings_card_border)) } }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        separator.color = context.getColor(R.color.galaxy_settings_card_border)
        separator.strokeWidth = GalaxyViews.dp(context, 1).coerceAtLeast(1).toFloat()
        var previous: View? = null
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            if (child is GalaxySettingRow && previous is GalaxySettingRow) {
                val start = (child.left + child.textInset).toFloat()
                val end = (child.right - GalaxyViews.dp(context, 20)).toFloat()
                canvas.drawLine(start, child.top.toFloat(), end, child.top.toFloat(), separator)
            }
            previous = child
        }
    }
}
