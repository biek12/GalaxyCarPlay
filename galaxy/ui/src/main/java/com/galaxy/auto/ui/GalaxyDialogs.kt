package com.galaxy.auto.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * 所有应用内弹窗共用一个入口；系统权限窗口仍由 Android 管理。
 *
 * 宿主可注入标题图标、按钮装饰、密度上下文与“草稿”文案替换，但模态框实现只有
 * [GalaxyModalDialog] 一份。线程：主线程；[showing] 只保存弱引用，不阻止页面回收。
 */
object GalaxyDialogs {
    private val showing = mutableListOf<WeakReference<GalaxyModalDialog>>()

    /** 新建构建器；[custom] 为 false 且无 closeRequest 时退回平台 AlertDialog。 */
    fun builder(context: Context) = Builder(context)

    /** 记录正在显示的模态框，供主题刷新使用。 */
    internal fun track(dialog: GalaxyModalDialog) {
        showing.removeAll { it.get() == null || it.get() === dialog }
        showing.add(WeakReference(dialog))
    }

    /** 移除已关闭的模态框。 */
    internal fun untrack(dialog: GalaxyModalDialog) {
        showing.removeAll { it.get() == null || it.get() === dialog }
    }

    /** Activity 的窗口遍历不包含 Dialog，配置更新后单独刷新属于该界面的弹窗。 */
    fun refresh(context: Context) {
        showing.removeAll { it.get() == null }
        showing.forEach { it.get()?.refreshPalette(context) }
    }

    /** 单个按钮的动作定义。 */
    internal data class Action(
        val label: CharSequence,
        val click: DialogInterface.OnClickListener?,
    )

    /** 构建期内容：只在创建弹窗时转交给模态框，不暴露给业务层。 */
    internal class Content {
        var dialogContext: Context? = null
        var titleIcon: ((String) -> Int?)? = null
        var decorate: ((Button) -> Unit)? = null
        var title: CharSequence = ""
        var message: CharSequence? = null
        var view: View? = null
        var items: Array<out CharSequence>? = null
        var checked = -1
        var singleChoice = false
        var itemClick: DialogInterface.OnClickListener? = null
        var itemEnabled: ((Int) -> Boolean)? = null
        var fixedHeight = false
        var cancelable = true
        var cancel: DialogInterface.OnCancelListener? = null
        var dismiss: DialogInterface.OnDismissListener? = null
        var closeRequest: (() -> Unit)? = null
        val actions = linkedMapOf<Int, Action>()
    }

    /** 与平台 Builder 同形的链式构建器，同时维护纯模态框所需的状态。 */
    class Builder(
        private val context: Context,
        private val custom: Boolean = true,
        private val dialogContext: Context = context,
        private val positiveLabel: (Int) -> Int = { it },
        private val titleIcon: ((String) -> Int?)? = null,
        private val decorate: ((Button) -> Unit)? = null,
    ) {
        private val content = Content()
        private val platform = AlertDialog.Builder(context)

        fun setTitle(value: Int) = setTitle(context.getString(value))

        fun setTitle(value: CharSequence) =
            apply {
                content.title = value
                platform.setTitle(value)
            }

        fun setMessage(value: Int) = setMessage(context.getString(value))

        fun setMessage(value: CharSequence) =
            apply {
                content.message = value
                platform.setMessage(value)
            }

        fun setFixedHeight() = apply { content.fixedHeight = true }

        fun setView(view: View) =
            apply {
                content.view = view
                platform.setView(view)
            }

        fun setCancelable(value: Boolean) =
            apply {
                content.cancelable = value
                platform.setCancelable(value)
            }

        fun setOnCancelListener(listener: DialogInterface.OnCancelListener) =
            apply {
                content.cancel = listener
                platform.setOnCancelListener(listener)
            }

        fun setOnDismissListener(listener: DialogInterface.OnDismissListener) =
            apply {
                content.dismiss = listener
                platform.setOnDismissListener(listener)
            }

        fun setOnCloseRequest(listener: () -> Unit) = apply { content.closeRequest = listener }

        fun setItems(items: Array<out CharSequence>, click: DialogInterface.OnClickListener) =
            apply {
                content.items = items
                content.itemClick = click
                content.singleChoice = false
                platform.setItems(items, click)
            }

        fun setSingleChoiceItems(
            items: Array<out CharSequence>,
            checked: Int,
            click: DialogInterface.OnClickListener,
        ) = setSingleChoiceItems(items, checked, null, click)

        /** 单选列表，可按位置禁用；禁用项不响应点击也不发布回调。 */
        fun setSingleChoiceItems(
            items: Array<out CharSequence>,
            checked: Int,
            enabled: ((Int) -> Boolean)?,
            click: DialogInterface.OnClickListener,
        ) = apply {
            content.items = items
            content.checked = checked
            content.itemClick = click
            content.singleChoice = true
            content.itemEnabled = enabled
            if (enabled == null) {
                platform.setSingleChoiceItems(items, checked, click)
            } else {
                platform.setSingleChoiceItems(
                    object : ArrayAdapter<CharSequence>(
                        context,
                        android.R.layout.simple_list_item_single_choice,
                        items,
                    ) {
                        override fun areAllItemsEnabled() = false

                        override fun isEnabled(position: Int) = enabled(position)

                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                            super.getView(position, convertView, parent).apply {
                                isEnabled = enabled(position)
                                GalaxyStyle.text(
                                    this as TextView,
                                    if (isEnabled) R.color.galaxy_ui_text else R.color.galaxy_ui_disabled,
                                )
                            }
                    },
                    checked,
                ) { dialog, index -> if (enabled(index)) click.onClick(dialog, index) }
            }
        }

        fun setPositiveButton(label: Int, click: DialogInterface.OnClickListener?) =
            setPositiveButton(context.getString(positiveLabel(label)), click)

        fun setPositiveButton(label: CharSequence, click: DialogInterface.OnClickListener?) =
            action(AlertDialog.BUTTON_POSITIVE, label, click)

        fun setNegativeButton(label: Int, click: DialogInterface.OnClickListener?) =
            setNegativeButton(context.getString(label), click)

        fun setNegativeButton(label: CharSequence, click: DialogInterface.OnClickListener?) =
            action(AlertDialog.BUTTON_NEGATIVE, label, click)

        fun setNeutralButton(label: Int, click: DialogInterface.OnClickListener?) =
            setNeutralButton(context.getString(label), click)

        fun setNeutralButton(label: CharSequence, click: DialogInterface.OnClickListener?) =
            action(AlertDialog.BUTTON_NEUTRAL, label, click)

        /** 创建弹窗；宿主注入项在此一次性转入内容。 */
        fun create(): AlertDialog {
            content.dialogContext = dialogContext
            content.titleIcon = titleIcon
            content.decorate = decorate
            return if (content.closeRequest != null ||
                custom
            ) {
                GalaxyModalDialog(context, content)
            } else {
                platform.create()
            }
        }

        /** 创建并显示。 */
        fun show(): AlertDialog = create().apply { show() }

        /** 登记一个按钮的动作与平台回退。 */
        private fun action(which: Int, label: CharSequence, click: DialogInterface.OnClickListener?) =
            apply {
                content.actions[which] = Action(label, click)
                when (which) {
                    AlertDialog.BUTTON_POSITIVE -> platform.setPositiveButton(label, click)
                    AlertDialog.BUTTON_NEGATIVE -> platform.setNegativeButton(label, click)
                    else -> platform.setNeutralButton(label, click)
                }
            }
    }
}
