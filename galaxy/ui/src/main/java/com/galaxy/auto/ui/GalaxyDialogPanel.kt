package com.galaxy.auto.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * 弹窗面板：静态骨架由 `galaxy_ui_dialog.xml` 提供，本类只保留实际可用高度的测量上限。
 *
 * 原实现用内部 `BoundedPanel` 保证内容再长也不超过屏幕 82%；固定高度模式（列表类弹窗）
 * 由调用方通过 [fixedHeight] 指定，测量时使用 EXACTLY 而非 AT_MOST。
 *
 * 线程：仅主线程测量；无线程或资源所有权。
 */
class GalaxyDialogPanel
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayout(context, attrs, defStyleAttr) {
        /** true 时按上限高度精确测量（列表弹窗），false 时允许内容自适应但不超过上限。 */
        var fixedHeight = false

        /** 以屏幕高度的 82% 为上限测量；窗口实际高度仍由 [GalaxyModalDialog.onStart] 决定。 */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val max = (resources.displayMetrics.heightPixels * .82f).toInt()
            val available =
                if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    max
                } else {
                    MeasureSpec.getSize(heightMeasureSpec).coerceAtMost(max)
                }
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(available, if (fixedHeight) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST),
            )
        }
    }
