package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.galaxy.auto.ui.GalaxyStyle

internal object L7Ui {
    fun bind(view: View, apply: () -> Unit) = GalaxyStyle.bind(view, apply)
    fun refresh(view: View) = GalaxyStyle.refresh(view)
    fun text(view: TextView, color: Int = R.color.product_ui_text) = GalaxyStyle.text(view, color)
    fun surface(view: View, color: Int = R.color.product_ui_surface, radius: Int = 4) = GalaxyStyle.surface(view,color,radius)
    fun rowFeedback(view: View) = GalaxyStyle.rowFeedback(view)
    fun button(view: Button, primary: Boolean = false, radius: Int = 4, compact: Boolean = false,
        surface: Int = R.color.product_ui_control, withIcon: Boolean = true) =
        GalaxyStyle.button(view,primary,radius,compact,surface,withIcon,L7Icons::decorate)
    fun input(view: android.widget.EditText) = GalaxyStyle.input(view)
    fun rounded(context: Context, color: Int, radius: Int) = GalaxyStyle.rounded(context,color,radius)
}
