package com.galaxy.autocluster.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.ImageView

/**
 * 主题预览比例控件：按仪表 1920×532 的宽高比，依据实际可用宽度自适应高度。
 *
 * XML 可实例化，故提供 `(Context, AttributeSet?, defStyleAttr)` 构造；FIT_CENTER 与预览底色
 * 由布局与颜色资源声明，本类只承担标准 ImageView 无法表达的自适应测量。
 * 线程/所有者：主线程测量；不持有缩略图票据、缓存或后台任务，`ImageView.tag` 仍由
 * [ThemeActions.thumbnail] 独占。
 */
internal class ThemeRatioImageView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : ImageView(context, attrs, defStyleAttr) {
        /**
         * 以实际宽度按 532/1920 向下取整得到高度，并用 EXACTLY 交给父实现。
         *
         * 测量例外：宽度可能为 0（未布局或父容器给 UNSPECIFIED），高度至少 1px，
         * 避免预览整体消失；不读取 heightMeasureSpec，比例是本控件的固定合同。
         */
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = (width * PREVIEW_HEIGHT.toFloat() / PREVIEW_WIDTH).toInt().coerceAtLeast(1)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }

        private companion object {
            /** 仪表预览基准宽度，单位 px；来自旧 RatioImage 的 1920×532。 */
            const val PREVIEW_WIDTH = 1920

            /** 仪表预览基准高度，单位 px。 */
            const val PREVIEW_HEIGHT = 532
        }
    }
