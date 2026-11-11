package com.galaxy.auto.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.galaxy.auto.ui.databinding.GalaxyUiSettingTextBinding

/**
 * 设置行的文字区：按实际文字宽度决定“当前值”是否与标题同行，长值与大字号自然增高而不截断。
 *
 * 静态骨架来自 `galaxy_ui_setting_text.xml`（四个 TextView）；本类只负责原实现中的 inline 判定、
 * 逐行排列和 RTL 摆放算法。
 *
 * 线程/所有者：只在测量与布局阶段读写子 View，必须位于主线程；无异步资源，无需取消。
 */
class GalaxySettingTextLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : ViewGroup(context, attrs, defStyleAttr) {
        private val binding = GalaxyUiSettingTextBinding.inflate(LayoutInflater.from(context), this)

        /** 标题文字。 */
        val label: TextView get() = binding.settingTextLabel

        /** 说明文字。 */
        val description: TextView get() = binding.settingTextDescription

        /** 当前值文字。 */
        val value: TextView get() = binding.settingTextValue

        /** 反馈文字。 */
        val feedback: TextView get() = binding.settingTextFeedback

        private val gap = resources.getDimension(R.dimen.galaxy_ui_text_gap).toInt()
        private val valueGap = resources.getDimension(R.dimen.galaxy_ui_text_value_gap).toInt()
        private var inline = false
        private var firstHeight = 0
        private var textHeight = 0

        init {
            // 接回既有 palette 绑定：现有实例在日夜切换后由 GalaxyStyle.refresh 重解析颜色，不重建 View。
            // 反馈色可由 setFeedback(error=...) 覆盖，覆盖后绑定即为该颜色，不在 refresh 中强行还原。
            GalaxyStyle.text(label, R.color.galaxy_ui_text)
            GalaxyStyle.text(value, R.color.galaxy_ui_muted)
            GalaxyStyle.text(description, R.color.galaxy_ui_muted)
            GalaxyStyle.text(feedback, R.color.galaxy_ui_muted)
        }

        /**
         * 先按整条文字区宽度试排“标题 + 尾部短值”，短值不超过 45% 且两者能同行时才内联，
         * 否则逐行向下排列；结果只影响本次测量，不写回任何业务状态。
         */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val naturalLabel = label.paint.measureText(label.text.toString())
            val naturalValue = value.paint.measureText(value.text.toString())
            inline = value.visibility == VISIBLE &&
                !value.text.contains('\n') &&
                naturalValue <= width * .45f &&
                naturalLabel + naturalValue + valueGap <= width

            fun measureText(view: View, available: Int, exact: Boolean = true) {
                view.measure(
                    MeasureSpec.makeMeasureSpec(
                        available.coerceAtLeast(0),
                        if (exact) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST,
                    ),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
            }

            val textWidth =
                if (inline) {
                    measureText(value, width, false)
                    width - value.measuredWidth - valueGap
                } else {
                    width
                }
            measureText(label, textWidth)
            firstHeight = label.measuredHeight
            textHeight = firstHeight
            listOf(value, description, feedback).forEach { view ->
                if (view.visibility != GONE && !(view === value && inline)) {
                    measureText(view, textWidth)
                    textHeight += gap + view.measuredHeight
                }
            }
            val height = maxOf(textHeight, if (inline) value.measuredHeight else 0)
            setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
        }

        /** 短值独立于标题与说明，按整条文字区居中，与右侧操作槽保持同一中心线。 */
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL

            fun place(view: View, x: Int, y: Int) {
                val start = if (rtl) width - x - view.measuredWidth else x
                view.layout(start, y, start + view.measuredWidth, y + view.measuredHeight)
            }

            val textTop = (height - textHeight) / 2
            place(label, 0, textTop)
            if (inline) place(value, width - value.measuredWidth, (height - value.measuredHeight) / 2)
            var y = textTop + firstHeight
            listOf(value, description, feedback).forEach { view ->
                if (view.visibility != GONE && !(view === value && inline)) {
                    y += gap
                    place(view, 0, y)
                    y += view.measuredHeight
                }
            }
        }
    }
