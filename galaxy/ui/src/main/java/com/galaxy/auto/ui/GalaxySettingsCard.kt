package com.galaxy.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * 设置卡片：背景来自 `drawable/galaxy_ui_card.xml`，只在相邻设置行之间绘制细分隔线。
 *
 * 分隔线是登记的绘制例外：起点取行的 [GalaxySettingRow.textInset]，终点留出右边距，
 * 不能把标题、提示或操作按钮误分成条目。
 *
 * 线程：仅主线程测量与绘制；背景在 [GalaxyStyle.bind] 中重解析，支持日夜刷新而不重建页面。
 */
class GalaxySettingsCard
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayout(context, attrs, defStyleAttr) {
        private val separator = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            orientation = VERTICAL
            clipToOutline = true
            GalaxyStyle.bind(this) { background = context.getDrawable(R.drawable.galaxy_ui_card) }
        }

        /** 逐个子项绘制分隔线；非可见项与相邻非设置行不参与。 */
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
                    val end =
                        (
                            child.right -
                                GalaxyViews.px(
                                    context,
                                    R.dimen.galaxy_ui_row_padding_horizontal,
                                )
                        ).toFloat()
                    canvas.drawLine(start, child.top.toFloat(), end, child.top.toFloat(), separator)
                }
                previous = child
            }
        }
    }
