package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.galaxy.auto.ui.GalaxySelection
import com.galaxy.auto.ui.GalaxyViews
import com.shilapi.xcertplay.host.R

/** 按 Flyme Auto 参考规范实现的 L7 原生组件；不依赖车机私有 widget。 */
internal object L7Components {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun backRow(context: Context, title: String, back: () -> Unit) = L7Header(context, title, onBack = back)

    /** 委托公共图标按钮；只在此覆盖宿主文字色，尺寸、命中区域与禁用规则不再另写一份。 */
    fun iconButton(context: Context, icon: Int, label: String, click: () -> Unit) =
        GalaxyViews.iconButton(context, icon, label, click).apply {
            L7Ui.bind(this) {
                imageTintList = ColorStateList.valueOf(context.getColor(R.color.product_ui_text))
            }
        }

    fun actionButton(context: Context, title: String, primary: Boolean = false, click: () -> Unit) =
        L7ActionButton(context).apply { bindActionButton(this, title, primary, click = click) }

    /** 显式图标不依赖按钮文案，车型名称更新及昼夜重绘后仍保留。 */
    fun actionButton(
        context: Context,
        title: String,
        icon: Int,
        primary: Boolean = false,
        click: () -> Unit,
    ) =
        L7ActionButton(context).apply { bindActionButton(this, title, primary, icon, click) }

    /**
     * 给 XML 中的原按钮补上与工厂相同的主次样式、图标和一次点击。
     * 禁用时不回调；图标只使用调用方传入的资源。
     */
    fun bindActionButton(
        button: L7ActionButton,
        title: String,
        primary: Boolean = false,
        icon: Int? = null,
        click: () -> Unit,
    ) {
        if (icon != null) button.actionIcon = icon
        button.text = title
        L7Ui.button(button, primary)
        button.setOnClickListener { if (button.isEnabled) click() }
    }

    /** 委托公共文字；宿主只覆盖颜色别名，字号/行距/字重只维护在 public UI。 */
    fun text(context: Context, value: String, secondary: Boolean = false) =
        GalaxyViews.text(context, value, secondary).apply {
            L7Ui.text(this, if (secondary) R.color.product_ui_muted else R.color.product_ui_text)
        }

    fun note(context: Context, value: String, warning: Boolean = false) =
        text(context, value, secondary = true).apply {
            setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 12))
            if (warning) L7Ui.text(this, R.color.product_ui_danger)
        }

    fun sectionTitle(context: Context, value: String) =
        L7Typography.text(context, value, L7Typography.Role.SECTION_TITLE)

    fun actionRow(
        context: Context,
        title: String,
        detail: String = "",
        icon: Int? = null,
        click: () -> Unit,
    ): L7SettingRow = L7SettingRow(context, title, detail, icon).apply { bindActionRow(this, click) }

    /**
     * 给 XML inflate 后的原设置行补上箭头、按压反馈和一次点击。
     * 标题、说明和图标保持布局或调用方已经写入的内容，禁用时不回调。
     */
    fun bindActionRow(row: L7SettingRow, click: () -> Unit) {
        val context = row.context
        row.setAccessory(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_l7_next)
                L7Ui.bind(this) {
                    imageTintList = ColorStateList.valueOf(context.getColor(R.color.product_ui_muted))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            dp(context, 32),
            dp(context, 32),
        )
        row.isFocusable = true
        L7Ui.rowFeedback(row)
        row.setOnClickListener { if (row.isEnabled) click() }
    }

    fun valueRow(context: Context, title: String, value: String, click: () -> Unit): L7SettingRow =
        actionRow(context, title, click = click).apply { setValue(value) }

    fun choice(
        parent: LinearLayout,
        title: String,
        options: List<String>,
        current: Int,
        reconnects: Boolean = true,
        context: Context = parent.context,
        save: (Int) -> Unit,
    ) {
        var selection = current.coerceIn(options.indices)
        lateinit var row: L7SettingRow
        row =
            valueRow(context, title, options[selection]) {
                select(
                    context,
                    title,
                    options,
                    selection,
                    context.getString(
                        if (context is GalaxyConfigurationContext) {
                            R.string.profile_update_draft
                        } else if (reconnects &&
                            CarPlayBackgroundSession.hasSession()
                        ) {
                            R.string.l7_save_next_connection
                        } else {
                            R.string.save
                        },
                    ),
                ) { chosen ->
                    save(chosen)
                    selection = chosen
                    row.setValue(options[chosen])
                }
            }
        parent.addView(row)
    }

    /** 分类入口仅用右侧箭头提示下一层，名称与说明保持原有布局。 */
    fun categoryRow(context: Context, title: String, detail: String, icon: Int, click: () -> Unit): L7SettingRow =
        actionRow(context, title, detail, icon, click)

    fun switchRow(
        context: Context,
        title: String,
        detail: String,
        checked: Boolean,
        save: (Boolean) -> Unit,
    ): L7SettingRow = GalaxyViews.switchRow(context, title, detail, checked, save)

    fun styleDialog(dialog: AlertDialog) {
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply {
                minHeight = dp(context, 64)
                L7Ui.button(this, primary = which == AlertDialog.BUTTON_POSITIVE, radius = 4)
            }
        }
    }

    /** 待选值只存在于本次弹窗；取消不保存，确认只提交发生变化的值。 */
    fun select(
        context: Context,
        title: String,
        options: List<String>,
        current: Int,
        applyLabel: String,
        onPreview: (Int) -> Unit = {},
        onDismiss: () -> Unit = {},
        optionEnabled: ((Int) -> Boolean)? = null,
        onApply: (Int) -> Unit,
    ): AlertDialog {
        val label =
            if (context is GalaxyConfigurationContext && context.editable) {
                context.getString(R.string.profile_update_draft)
            } else {
                applyLabel
            }
        return GalaxySelection.select(
            context,
            title,
            options,
            current,
            label,
            onPreview,
            onDismiss,
            optionEnabled,
            builder = L7Dialogs.builder(context),
            onApply = onApply,
        )
    }
}
