package com.galaxy.auto.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * 公共控件的唯一工厂：静态骨架由 `res/layout/galaxy_ui_*.xml` 提供，本对象只绑定角色、颜色与事件。
 *
 * 线程/所有者：全部在主线程创建与使用；不持有连接、权限或业务设置。
 * 兼容：原有重载（含 `secondary` 与 `CharSequence` 语义）保持不变，旧 L7 入口继续委托到这里。
 */
object GalaxyViews {
    /** 按密度换算 dp；保留历史截断行为，供尺寸不入资源库的运行时计算使用。 */
    fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()

    /** 读取尺寸资源并按历史截断行为取整。 */
    fun px(context: Context, id: Int) = context.resources.getDimension(id).toInt()

    /** 按文字角色创建文字视图。 */
    fun text(
        context: Context,
        value: String,
        role: GalaxyTypography.Role = GalaxyTypography.Role.LABEL,
    ): TextView =
        inflateText(context).also { view ->
            GalaxyTypography.apply(view, role.size, role.line, role.medium)
            view.text = value
            GalaxyStyle.text(
                view,
                if (role.secondary) R.color.galaxy_ui_muted else R.color.galaxy_ui_text,
            )
        }

    /** 兼容重载：主/次文字，16/20sp 与 3dp 行距保持原样。 */
    fun text(context: Context, value: String, secondary: Boolean): TextView =
        inflateText(context).also { view ->
            view.text = value
            view.textSize = if (secondary) 16f else 20f
            view.typeface =
                Typeface.create(
                    if (secondary) "sans-serif" else "sans-serif-medium",
                    Typeface.NORMAL,
                )
            view.includeFontPadding = false
            view.setLineSpacing(dp(context, 3).toFloat(), 1f)
            GalaxyStyle.text(
                view,
                if (secondary) R.color.galaxy_ui_muted else R.color.galaxy_ui_text,
            )
        }

    /** 分组标题。 */
    fun sectionTitle(context: Context, value: String) =
        text(context, value, GalaxyTypography.Role.SECTION_TITLE)

    /** 独立分组容器：表面色、4dp 圆角与 20dp 内边距由布局资源提供。 */
    fun group(context: Context): LinearLayout =
        LayoutInflater.from(context).inflate(R.layout.galaxy_ui_group, null, false) as LinearLayout

    /** 主/次操作按钮。 */
    fun button(
        context: Context,
        label: String,
        primary: Boolean = false,
        click: () -> Unit,
    ): GalaxyActionButton =
        inflateButton(context).also { button ->
            button.text = label
            GalaxyStyle.button(button, primary, withIcon = false)
            button.setOnClickListener { if (button.isEnabled) click() }
        }

    /** 纯文字按钮：无填充、保留 64dp 命中高度与强调色文字。 */
    fun textButton(context: Context, label: String, click: () -> Unit): GalaxyActionButton =
        inflateButton(context).also { button ->
            button.text = label
            button.isAllCaps = false
            button.stateListAnimator = null
            button.backgroundTintList = null
            GalaxyTypography.apply(button, 18, 26, false)
            button.minHeight = dp(context, 64)
            button.minimumHeight = button.minHeight
            button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            button.setPadding(0, dp(context, 8), dp(context, 12), dp(context, 8))
            GalaxyStyle.bind(button) {
                button.setTextColor(context.getColorStateList(R.color.galaxy_ui_action_text))
                button.background = context.getDrawable(R.drawable.galaxy_ui_button_text)
            }
            button.setOnClickListener { if (button.isEnabled) click() }
        }

    /** 导航/分段按钮：选中态由 `isSelected` 驱动，颜色与背景来自同一组 selector。 */
    fun navigationStyle(button: Button) =
        GalaxyStyle.bind(button) {
            button.setTextColor(button.context.getColorStateList(R.color.galaxy_ui_navigation_text))
            button.background = button.context.getDrawable(R.drawable.galaxy_ui_navigation_background)
        }

    /** 兼容入口：动作按钮等同主/次按钮。 */
    fun actionButton(context: Context, title: String, primary: Boolean = false, click: () -> Unit) =
        button(context, title, primary, click)

    /** 图标按钮：64dp 命中区域，图标按文字色 tint。 */
    fun iconButton(
        context: Context,
        icon: Int,
        label: String,
        click: () -> Unit,
    ): ImageButton =
        (
            LayoutInflater.from(context).inflate(
                R.layout.galaxy_ui_icon_button,
                null,
                false,
            ) as ImageButton
        ).also { button ->
            button.setImageResource(icon)
            button.contentDescription = label
            button.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            button.minimumWidth = dp(context, 64)
            button.minimumHeight = dp(context, 64)
            GalaxyStyle.bind(button) {
                button.imageTintList = ColorStateList.valueOf(context.getColor(R.color.galaxy_ui_text))
                button.background = context.getDrawable(R.drawable.galaxy_ui_icon_button)
            }
            button.setOnClickListener { if (button.isEnabled) click() }
        }

    /**
     * 把开关插入设置行操作槽；这是“整行点击 + 外部回填不回调”的唯一实现。
     *
     * [leading] 为 true 时开关排在文字之前（CarPlay 兼容入口），否则留在行末（AutoService 开关）。
     * [slotWidth] 为空时使用公共槽宽 82dp。
     */
    fun attachSwitch(
        row: GalaxySettingRow,
        title: String,
        checked: Boolean,
        leading: Boolean = true,
        slotWidth: Int? = null,
        save: (Boolean) -> Unit,
    ): Switch {
        val context = row.context
        val control =
            inflateSwitch(context).apply {
                contentDescription = title
                isChecked = checked
                minimumHeight = dp(context, 64)
                GalaxySwitchStyle.apply(this)
                setOnCheckedChangeListener { _, value -> if (!row.updatingSwitch) save(value) }
            }
        row.setAccessory(
            control,
            slotWidth = slotWidth ?: px(context, R.dimen.galaxy_ui_row_switch_slot),
            leading = leading,
        )
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.setOnClickListener { if (row.isEnabled && control.isEnabled) control.performClick() }
        row.isFocusable = false
        GalaxyStyle.rowFeedback(row)
        return control
    }

    /** 开关行：标题 + 说明 + 前置开关。 */
    fun switchRow(
        context: Context,
        title: String,
        description: String,
        checked: Boolean,
        save: (Boolean) -> Unit,
    ): GalaxySettingRow =
        GalaxySettingRow(context, title, description).also { row ->
            attachSwitch(row, title, checked, leading = true, save = save)
        }

    /** 文字视图：只取其静态属性，父级 LayoutParams 由调用方决定。 */
    private fun inflateText(context: Context): TextView =
        LayoutInflater.from(context).inflate(R.layout.galaxy_ui_text, null, false) as TextView

    /** 按钮视图：XML 中即 [GalaxyActionButton]，样式由 `GalaxyUi.Button` 提供。 */
    private fun inflateButton(context: Context): GalaxyActionButton =
        LayoutInflater
            .from(context)
            .inflate(R.layout.galaxy_ui_button, null, false) as GalaxyActionButton

    /** 开关视图：轨道/滑块来自公共 drawable，避免再画一套。 */
    private fun inflateSwitch(context: Context): Switch =
        LayoutInflater.from(context).inflate(R.layout.galaxy_ui_switch, null, false) as Switch
}
