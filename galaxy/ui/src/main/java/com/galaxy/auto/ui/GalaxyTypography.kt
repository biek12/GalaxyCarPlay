package com.galaxy.auto.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import kotlin.math.roundToInt

object GalaxyTypography {
    enum class Role(val size: Int, val line: Int, val medium: Boolean = false, val secondary: Boolean = true) {
        PAGE_TITLE(24,32,true,false), SECTION_TITLE(18,26,true), LABEL(20,28,false,false),
        VALUE(18,26), DESCRIPTION(16,24), FEEDBACK(16,24)
    }
    fun apply(view: TextView, size: Int, line: Int, medium: Boolean) {
        view.textSize=size.toFloat()
        view.typeface=Typeface.create(if(medium) "sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        view.includeFontPadding=false
        view.setLineHeight((line*view.resources.displayMetrics.scaledDensity).roundToInt())
    }
    fun text(context: Context,value: String,role: Role) = TextView(context).apply {
        text=value; apply(this,role.size,role.line,role.medium)
        GalaxyStyle.text(this,if(role.secondary) R.color.galaxy_ui_muted else R.color.galaxy_ui_text)
    }
}
