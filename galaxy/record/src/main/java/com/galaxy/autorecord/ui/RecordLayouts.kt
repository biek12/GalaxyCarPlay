package com.galaxy.autorecord.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 固定 16:9 的画面占位容器。
 *
 * 纪律（样式基线 §6）：视频格一律 16:9、不裁切、不拉伸。这里只按可用宽度换算高度，
 * 不让内容反过来改变比例；本轮内部没有真实画面，只放状态文字与方向标签。
 */
internal class Aspect16x9(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (width > 0) Math.round(width * 9f / 16f) else 0
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}

/**
 * 按可用宽度自动换行的横排容器。
 *
 * 用途：车辆数据条的每组项目。窄屏上 7–9 个项目放不进一行时，
 * 宁可换行也不省略字段或压缩到不可读（规格 §2.1 触控/可读性要求优先）。
 */
internal class WrapRow(context: Context, private val gapX: Int, private val gapY: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
        var x = 0
        var y = 0
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val width = child.measuredWidth.coerceAtMost(maxWidth)
            if (x > 0 && x + width > maxWidth) {
                x = 0
                y += lineHeight + gapY
                lineHeight = 0
            }
            x += width + gapX
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        setMeasuredDimension(maxWidth, resolveSize(y + lineHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val maxWidth = width
        var x = 0
        var y = 0
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val childWidth = child.measuredWidth.coerceAtMost(maxWidth)
            if (x > 0 && x + childWidth > maxWidth) {
                x = 0
                y += lineHeight + gapY
                lineHeight = 0
            }
            child.layout(x, y, x + childWidth, y + child.measuredHeight)
            x += childWidth + gapX
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }
}

