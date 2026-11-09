package com.galaxy.autorecord.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 64dp 标题栏。
 *
 * 与 CarPlay 的 L7Header 差异（规格 §2.1 明确要求）：
 * 左槽只在二级页出现（一级页留空，不创建返回按钮）；
 * 右槽改为**内容自适应宽**，高度仍 64dp，用来放"未录制/存储"等徽标；
 * 标题保持单行，徽标放不下时由页面自己降级为图标+极短值，不挤压标题文字或换行。
 */
internal class RecordTitleBar(context: Context, private val ui: RecordUi) : LinearLayout(context) {
    private val leftSlot = FrameLayout(context)
    private val titleView: TextView = ui.text("", RecordRole.PAGE_TITLE).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val rightSlot: LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.dp(70)
        setPadding(ui.dp(16), 0, ui.dp(16), 0)
        addView(leftSlot, LayoutParams(0, ui.dp(48)))
        addView(titleView, LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(4) })
        addView(rightSlot, LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
    }

    fun bind(title: String, showBack: Boolean, backLabel: String, onBack: () -> Unit) {
        titleView.maxLines = if ('\n' in title) 2 else 1
        titleView.text = if ('\n' in title) android.text.SpannableString(title).apply {
            setSpan(android.text.style.RelativeSizeSpan(.6f), title.indexOf('\n') + 1, title.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else title
        leftSlot.removeAllViews()
        leftSlot.layoutParams = LayoutParams(if (showBack) ui.dp(48) else 0, ui.dp(48))
        if (showBack) {
            leftSlot.addView(
                ui.iconButton("back", backLabel, ui.textColor, onBack),
                FrameLayout.LayoutParams(ui.dp(48), ui.dp(48)),
            )
        }
    }

    fun bindRight(build: (LinearLayout) -> Unit) {
        rightSlot.removeAllViews()
        build(rightSlot)
    }

    /** 徽标之间统一 8dp 间距，避免页面各自散落 margin。 */
    fun addBadge(badge: View) {
        rightSlot.addView(badge, LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
    }
}

/**
 * 一级页左侧导航栏：120dp，图标与文字上下排布，仅导航保留此结构。
 * 选中：底色用 selected、图标与文字用 accent；未选中用 text。
 */
internal class RecordNavRail(
    context: Context,
    private val ui: RecordUi,
    private val onSelect: (MainPage) -> Unit,
) : LinearLayout(context) {

    private class Item(
        val page: MainPage,
        val root: LinearLayout,
        val icon: ImageView,
        val label: TextView,
    )

    private val items = mutableListOf<Item>()

    init {
        orientation = VERTICAL
        setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
        background = ui.navRailBackground()
        for (page in MainPage.entries) {
            val icon = ui.glyph(page.glyph, ui.textColor, 0)
            val label = ui.text(page.label, RecordRole.NAV).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }
            val item = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = ui.dp(98)
                setPadding(ui.dp(4), ui.dp(13), ui.dp(4), ui.dp(13))
                isClickable = true
                isFocusable = true
                contentDescription = page.label
                setOnClickListener { onSelect(page) }
            }
            item.addView(icon, LayoutParams(ui.dp(29), ui.dp(29)))
            item.addView(label, LayoutParams(-1, -2).apply { topMargin = ui.dp(9) })
            addView(item, LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
            items += Item(page, item, icon, label)
        }
        select(MainPage.LIVE)
    }

    fun select(page: MainPage) {
        for (item in items) {
            val selected = item.page == page
            item.root.isSelected = selected
            item.root.background = RippleDrawable(
                ColorStateList.valueOf(ui.rippleColor),
                ui.rounded(if (selected) ui.selectedColor else Color.TRANSPARENT, 4),
                null,
            )
            item.icon.setImageDrawable(ui.glyphDrawable(item.page.glyph, if (selected) ui.accent else ui.textColor))
            item.label.setTextColor(if (selected) ui.accent else ui.textColor)
        }
    }
}

