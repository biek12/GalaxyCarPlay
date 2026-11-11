package com.galaxy.auto.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.galaxy.auto.ui.databinding.GalaxyUiSettingRowBinding

/**
 * 设置行：图标列、文字区与操作槽三段的静态骨架来自 `galaxy_ui_setting_row.xml`。
 *
 * 普通参数不占图标列，只读信息不占操作列；标题、说明、当前值与反馈各自独立更新。
 * 开关等操作控件由 [setAccessory] 插入同一操作槽，本类不持有任何业务或服务状态。
 *
 * 线程/所有者：全部在主线程使用；[setAccessory] 会移动自己的槽位，调用方不要缓存槽内 View 的索引。
 */
class GalaxySettingRow
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayout(context, attrs, defStyleAttr) {
        private val binding = GalaxyUiSettingRowBinding.inflate(LayoutInflater.from(context), this)

        /** 文字区；XML 与代码构造共用同一实例。 */
        private val labels: GalaxySettingTextLayout get() = binding.settingRowText

        /** 前置图标。 */
        private val iconView: ImageView get() = binding.settingRowIcon

        /** 操作槽；开关、箭头等由调用方插入。 */
        private val accessory: FrameLayout get() = binding.settingRowAccessory

        /** 标题文字，供兼容入口与测试读取。 */
        val titleView: TextView get() = labels.label

        /** 说明文字。 */
        val descriptionView: TextView get() = labels.description

        /** 当前值文字。 */
        val valueView: TextView get() = labels.value

        /** 反馈文字。 */
        val feedbackView: TextView get() = labels.feedback

        /** 外部回填开关期间为 true；监听器据此跳过业务回调。 */
        var updatingSwitch = false
            private set

        private var hasIcon = false
        private var leadingAccessoryWidth = 0

        /** 操作槽默认位置：XML 属性 `galaxyAccessoryPosition`，默认末位。 */
        private var accessoryLeading = false

        /** 文字区左缘到行起点的距离；卡片分隔线用它对齐文字列。 */
        val textInset: Int
            get() =
                GalaxyViews.px(context, R.dimen.galaxy_ui_row_padding_horizontal) +
                    if (hasIcon) {
                        GalaxyViews.px(context, R.dimen.galaxy_ui_row_icon_size) +
                            GalaxyViews.px(context, R.dimen.galaxy_ui_row_icon_gap)
                    } else {
                        0
                    } + leadingAccessoryWidth

        init {
            applyRowStyle(attrs, defStyleAttr)
            val array = context.obtainStyledAttributes(attrs, R.styleable.GalaxySettingRow)
            try {
                applyTitle(array.getString(R.styleable.GalaxySettingRow_galaxyTitle).orEmpty())
                setDescription(
                    array.getString(R.styleable.GalaxySettingRow_galaxyDescription).orEmpty(),
                )
                array.getString(R.styleable.GalaxySettingRow_galaxyValue)?.let(::setValue)
                array
                    .getResourceId(R.styleable.GalaxySettingRow_galaxyLeadingIcon, 0)
                    .takeIf { it != 0 }
                    ?.let(::setLeadingIcon)
                accessoryLeading =
                    array.getInt(R.styleable.GalaxySettingRow_galaxyAccessoryPosition, ACCESSORY_END) ==
                    ACCESSORY_START
            } finally {
                array.recycle()
            }
        }

        /** 代码构造兼容入口：标题、说明与可选前置图标。 */
        constructor(
            context: Context,
            title: String,
            description: String = "",
            icon: Int? = null,
        ) : this(context) {
            applyTitle(title)
            setDescription(description)
            setLeadingIcon(icon)
        }

        /** 更新标题；不复用 [setValue] 的隐藏语义，空标题仍保留行高。 */
        fun setTitle(value: String) = applyTitle(value)

        /** 更新说明；空字符串隐藏该行。 */
        fun setDescription(value: String) = update(descriptionView, value)

        /** 更新当前值；空字符串隐藏该行。 */
        fun setValue(value: String) = update(valueView, value)

        /** 更新反馈文案与色调；反馈区作为无障碍实时区域播报。 */
        fun setFeedback(value: String, error: Boolean = false) {
            update(feedbackView, value)
            GalaxyStyle.text(
                feedbackView,
                if (error) R.color.galaxy_ui_danger else R.color.galaxy_ui_muted,
            )
            feedbackView.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        /** 设置/替换前置图标；为空时整列收起，文字区左移。 */
        fun setLeadingIcon(icon: Int?) {
            hasIcon = icon != null
            iconView.visibility = if (hasIcon) VISIBLE else GONE
            if (icon != null) {
                iconView.setImageResource(icon)
                GalaxyStyle.bind(iconView) {
                    iconView.imageTintList =
                        ColorStateList.valueOf(context.getColor(R.color.galaxy_ui_text))
                }
            }
            requestLayout()
        }

        /**
         * 把操作控件插入操作槽。`leading` 为 true 时槽位移到文字之前（开关行），否则停在末位（箭头/状态）。
         * 槽宽由调用方决定，视觉尺寸与整行命中区域分别处理。
         */
        fun setAccessory(
            view: View,
            width: Int = LayoutParams.WRAP_CONTENT,
            height: Int = LayoutParams.WRAP_CONTENT,
            slotWidth: Int = GalaxyViews.px(context, R.dimen.galaxy_ui_row_accessory_width),
            leading: Boolean = accessoryLeading,
        ) {
            accessory.removeAllViews()
            leadingAccessoryWidth = if (leading) slotWidth else 0
            removeView(accessory)
            addView(
                accessory,
                if (leading) 0 else childCount,
                LayoutParams(slotWidth, LayoutParams.WRAP_CONTENT),
            )
            accessory.addView(
                view,
                FrameLayout.LayoutParams(
                    width,
                    height,
                    (if (leading) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL,
                ),
            )
            accessory.visibility = VISIBLE
            view.isEnabled = isEnabled
        }

        /** 外部服务状态回填不等于用户操作，不能再次触发权限或服务调用。 */
        fun setSwitchChecked(checked: Boolean) {
            val control = accessory.getChildAt(0) as? CompoundButton ?: return
            updatingSwitch = true
            try {
                control.isChecked = checked
            } finally {
                updatingSwitch = false
            }
        }

        /** 禁用只减弱控件；名称、确认值和禁用原因保持可读。 */
        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            accessory.alpha = if (enabled) 1f else .4f
            for (index in 0 until accessory.childCount) accessory.getChildAt(index).isEnabled = enabled
        }

        /** 文字变化后同步可见性与行高；长值或说明存在时保持 96dp 下限。 */
        private fun update(view: TextView, value: String) {
            if (view.text.toString() != value) view.text = value
            view.visibility = if (value.isEmpty()) GONE else VISIBLE
            minimumHeight =
                GalaxyViews.px(
                    context,
                    if (descriptionView.visibility == VISIBLE || feedbackView.visibility == VISIBLE) {
                        R.dimen.galaxy_ui_row_min_height_detail
                    } else {
                        R.dimen.galaxy_ui_row_min_height
                    },
                )
        }

        /** 标题之外文字换行导致增高时，行高与操作槽中心线一起抬高。 */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val detailHeight = GalaxyViews.px(context, R.dimen.galaxy_ui_row_min_height_detail)
            if (labels.measuredHeight > titleView.measuredHeight && measuredHeight < detailHeight) {
                setMeasuredDimension(measuredWidth, resolveSize(detailHeight, heightMeasureSpec))
            }
        }

        /** 设置标题文字。 */
        private fun applyTitle(value: String) {
            titleView.text = value
        }

        /**
         * 应用 `GalaxyUi.SettingRow` 静态样式：orientation/gravity/padding/minHeight 只有这一个来源。
         *
         * 显式属性优先，其次 defStyleAttr，最后 defStyleRes；XML 与代码构造走同一入口。
         * 80/96dp 的运行时切换仍由内容状态决定，不属于静态样式。
         */
        private fun applyRowStyle(
            attrs: AttributeSet?,
            defStyleAttr: Int,
        ) {
            val styled =
                context.obtainStyledAttributes(attrs, ROW_ATTRS, defStyleAttr, R.style.GalaxyUi_SettingRow)
            try {
                orientation = styled.getInt(0, HORIZONTAL)
                gravity = styled.getInt(1, Gravity.CENTER_VERTICAL)
                setPaddingRelative(
                    styled.getDimensionPixelSize(2, 0),
                    styled.getDimensionPixelSize(3, 0),
                    styled.getDimensionPixelSize(4, 0),
                    styled.getDimensionPixelSize(5, 0),
                )
                minimumHeight = styled.getDimensionPixelSize(6, 0)
            } finally {
                styled.recycle()
            }
        }

        private companion object {
            const val ACCESSORY_START = 0
            const val ACCESSORY_END = 1

            /** 与 [applyRowStyle] 中下标一一对应的 View 属性集合。 */
            val ROW_ATTRS =
                intArrayOf(
                    android.R.attr.orientation,
                    android.R.attr.gravity,
                    android.R.attr.paddingStart,
                    android.R.attr.paddingTop,
                    android.R.attr.paddingEnd,
                    android.R.attr.paddingBottom,
                    android.R.attr.minHeight,
                )
        }
    }
