package com.galaxy.auto.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout

/**
 * 选择与分段切换：待选状态只属于本次弹窗，关闭/取消不提交，确认只调用一次。
 *
 * 静默确认、取消与焦点恢复仍由 [GalaxyDialogs] 的模态框实现承载；本对象只组织草稿与回调。
 * 线程：主线程；不持有业务连接或配置。
 */
object GalaxySelection {
    /**
     * 单选弹窗：修改前预览，确认只提交一次变化值。
     *
     * [optionEnabled] 返回 false 的项不可选择；[builder] 允许宿主注入密度与文案（CarPlay）。
     */
    fun select(
        context: Context,
        title: String,
        options: List<String>,
        current: Int,
        applyLabel: String,
        onPreview: (Int) -> Unit = {},
        onDismiss: () -> Unit = {},
        optionEnabled: ((Int) -> Boolean)? = null,
        builder: GalaxyDialogs.Builder? = null,
        onApply: (Int) -> Unit,
    ): AlertDialog {
        require(options.isNotEmpty() && current in options.indices)
        var pending = current
        var committed = false
        val dialog =
            (builder ?: GalaxyDialogs.builder(context))
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), current, optionEnabled) { dialog, index ->
                    if (optionEnabled?.invoke(index) == false) return@setSingleChoiceItems
                    pending = index
                    onPreview(index)
                    (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = pending != current
                }.setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(applyLabel, null)
                .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.setOnShowListener {
            val apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            apply.isEnabled = false
            apply.setOnClickListener {
                if (pending == current || committed || optionEnabled?.invoke(pending) == false) {
                    return@setOnClickListener
                }
                committed = true
                dialog.dismiss()
                onApply(pending)
            }
        }
        dialog.show()
        return dialog
    }

    /** App 导航/分段选择只发布选中索引，内容与返回行为由宿主决定。 */
    fun segments(context: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        require(labels.isNotEmpty() && selected in labels.indices)
        val layout = LayoutInflater.from(context).inflate(R.layout.galaxy_ui_segments, null, false) as LinearLayout
        labels.forEachIndexed { index, label ->
            layout.addView(
                GalaxyViews.button(context, label, index == selected) { onSelect(index) },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
        }
        return layout
    }
}
