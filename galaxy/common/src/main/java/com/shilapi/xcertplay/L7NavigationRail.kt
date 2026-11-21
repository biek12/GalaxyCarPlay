package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.host.databinding.ItemL7NavigationBinding
import com.shilapi.xcertplay.host.databinding.ViewL7NavigationRailBinding

/** 连接动作位于画面下方，详细功能统一归入设置。 */
internal class L7NavigationRail(
    context: Context,
    onSelect: (String) -> Unit,
) : LinearLayout(context) {
    private val buttons = mutableMapOf<String, View>()
    private val labels = mutableMapOf<String, TextView>()
    private var connected = false
    private val connectionDot: View

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        val padding = resources.getDimensionPixelSize(R.dimen.galaxy_settings_nav_padding_vertical)
        setPadding(0, padding, 0, padding)
        val binding = ViewL7NavigationRailBinding.inflate(LayoutInflater.from(context), this)
        val items =
            listOf(
                Item("home", R.string.l7_nav_picture, R.drawable.ic_l7_projection),
                Item("connection", R.string.l7_nav_connect, R.drawable.ic_l7_connection),
                Item("settings", R.string.settings, R.drawable.ic_l7_settings),
                Item("car-home", R.string.l7_nav_car_home, R.drawable.ic_l7_home),
                Item("exit", R.string.l7_nav_exit, R.drawable.ic_l7_exit),
            )
        var homeDot: View? = null
        for ((key, title, icon) in items) {
            val item = ItemL7NavigationBinding.inflate(LayoutInflater.from(context), binding.navigationItems, false)
            val row = item.navigationItem
            row.isSelected = key == "home"
            row.contentDescription = context.getString(title)
            row.setOnClickListener { onSelect(key) }
            item.navigationIcon.setImageResource(icon)
            item.navigationLabel.setText(title)
            if (key == "home") {
                homeDot = item.navigationDot
            } else {
                item.navigationDot.visibility = GONE
            }

            fun foreground() =
                context.getColor(
                    when {
                        key == "home" && connected -> R.color.product_ui_connected
                        row.isSelected -> R.color.product_ui_accent
                        else -> R.color.product_ui_text
                    },
                )
            L7Ui.bind(row) { row.setBackgroundResource(R.drawable.galaxy_navigation_item) }
            L7Ui.bind(item.navigationIcon) {
                if (key == "connection") {
                    item.navigationIcon.setImageResource(if (connected) R.drawable.ic_l7_refresh else icon)
                }
                item.navigationIcon.imageTintList = ColorStateList.valueOf(foreground())
            }
            L7Ui.bind(item.navigationLabel) { item.navigationLabel.setTextColor(foreground()) }
            L7Ui.bind(item.navigationDot) { item.navigationDot.setBackgroundResource(R.drawable.galaxy_navigation_dot) }
            binding.navigationItems.addView(row)
            buttons[key] = row
            labels[key] = item.navigationLabel
        }
        connectionDot = homeDot ?: error("首页导航项缺少状态点")
        L7Ui.refresh(this)
    }

    /** 同步画面和连接项的连接外观；状态点不改变菜单尺寸。 */
    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        connectionDot.visibility = if (value) View.VISIBLE else View.INVISIBLE
        buttons["home"]?.let {
            it.contentDescription =
                context.getString(if (value) R.string.l7_nav_picture_connected else R.string.l7_nav_picture)
            L7Ui.refresh(it)
        }
        buttons["connection"]?.let { item ->
            val title = context.getString(if (value) R.string.l7_nav_reconnect else R.string.l7_nav_connect)
            item.contentDescription = title
            labels["connection"]?.text = title
            L7Ui.refresh(item)
        }
    }

    /** 页面切换只更新选中态，复用原有按钮和布局。 */
    fun select(destination: String) {
        buttons.forEach { (key, button) -> button.isSelected = key == destination }
        L7Ui.refresh(this)
    }

    private data class Item(
        val key: String,
        val title: Int,
        val icon: Int,
    )
}
