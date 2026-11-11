package com.galaxy.auto.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.galaxy.auto.ui.databinding.GalaxyUiDialogBinding

/**
 * 竖屏模态框：标题、单一滚动内容区与固定操作区，保留 AlertDialog 的确认/取消契约。
 *
 * 静态骨架来自 `galaxy_ui_dialog.xml`（header / 滚动 body / footer 槽），列表项来自
 * `galaxy_ui_dialog_choice.xml`。动态内容只往槽位里填，不在 onCreate 重拼整页。
 *
 * 线程/所有者：主线程；窗口生命周期与 [GalaxyDialogs] 的跟踪一致，`closeRequest` 优先于默认关闭。
 */
internal class GalaxyModalDialog(
    private val owner: Context,
    private val content: GalaxyDialogs.Content,
) : AlertDialog(content.dialogContext ?: owner) {
    private val uiContext = context
    private val returnFocus = GalaxyDialogFocus(owner)
    private val actions = mutableMapOf<Int, Button>()
    private var choices: ListView? = null
    private var close: ImageButton? = null
    private var canCancel = content.cancelable
    private lateinit var binding: GalaxyUiDialogBinding

    /** 主题变化后重解析弹窗内资源；只对仍显示的同一宿主生效。 */
    fun refreshPalette(owner: Context) {
        if (owner !== this.owner || !isShowing) return
        window?.decorView?.let(GalaxyStyle::refresh)
        val list = choices
        val checked = list?.checkedItemPosition ?: -1
        (list?.adapter as? BaseAdapter)?.notifyDataSetChanged()
        list?.divider = ColorDrawable(uiContext.getColor(R.color.galaxy_ui_control))
        if (checked >= 0) list?.setItemChecked(checked, true)
    }

    init {
        // 在调用者配置监听器前接入默认回调，不在 onCreate 覆盖输入清理和预听资源释放。
        setCancelable(content.cancelable)
        setCanceledOnTouchOutside(content.cancelable)
        setOnCancelListener(content.cancel)
        setOnDismissListener(content.dismiss)
    }

    override fun getButton(whichButton: Int): Button? = actions[whichButton]

    override fun getListView(): ListView? = choices

    override fun cancel() {
        // 任务弹窗先处理终止确认，不能先关闭窗口再通知业务。
        content.closeRequest?.let {
            it()
            return
        }
        super.cancel()
    }

    override fun setCancelable(flag: Boolean) {
        super.setCancelable(flag)
        canCancel = flag
        close?.isEnabled = flag
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        binding = GalaxyUiDialogBinding.inflate(LayoutInflater.from(uiContext))
        val panel = binding.root
        panel.fixedHeight = content.fixedHeight
        bindHeader()
        bindBody()
        bindFooter()
        setView(panel, 0, 0, 0, 0)
        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        GalaxyDialogs.track(this)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val maxWidth = GalaxyViews.px(uiContext, R.dimen.galaxy_ui_dialog_max_width)
            val width = (uiContext.resources.displayMetrics.widthPixels * .88f).toInt().coerceAtMost(maxWidth)
            val height =
                if (content.fixedHeight) {
                    (uiContext.resources.displayMetrics.heightPixels * .72f).toInt()
                } else {
                    WindowManager.LayoutParams.WRAP_CONTENT
                }
            setLayout(width, height)
            setDimAmount(.48f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onStop() {
        GalaxyDialogs.untrack(this)
        super.onStop()
        returnFocus.restore()
    }

    /** 标题、可选业务图标与关闭键；关闭键是否可用跟随 cancelable。 */
    private fun bindHeader() {
        content.titleIcon?.invoke(content.title.toString())?.let { icon ->
            binding.dialogIcon.apply {
                setImageResource(icon)
                visibility = View.VISIBLE
                GalaxyStyle.bind(this) {
                    imageTintList = ColorStateList.valueOf(uiContext.getColor(R.color.galaxy_ui_text))
                }
            }
        }
        binding.dialogTitle.text = content.title
        close =
            binding.dialogClose.apply {
                isEnabled = canCancel
                GalaxyStyle.bind(this) {
                    imageTintList = ColorStateList.valueOf(uiContext.getColor(R.color.galaxy_ui_muted))
                }
                setOnClickListener { if (canCancel) cancel() }
            }
    }

    /** 消息、列表或嵌入内容只进入同一个滚动容器。 */
    private fun bindBody() {
        content.message?.let { message ->
            binding.dialogMessage.apply {
                text = message.toString()
                visibility = View.VISIBLE
            }
        }
        content.items?.let { items -> binding.dialogBody.addView(choiceList(items)) }
        content.view?.let { view ->
            // 内容只保留一个滚动容器，输入框和错误提示随键盘缩小窗口仍可达。
            val inner =
                if (view is ScrollView && view.childCount == 1) {
                    view.getChildAt(0).also { view.removeView(it) }
                } else {
                    view
                }
            (inner.parent as? ViewGroup)?.removeView(inner)
            styleInputs(inner)
            binding.dialogBody.addView(inner, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** 中性按钮独占一行；取消/确认按字号缩放决定横向或纵向排列。 */
    private fun bindFooter() {
        val neutral = content.actions[BUTTON_NEUTRAL]
        if (neutral != null) {
            binding.dialogFooter.addView(
                action(BUTTON_NEUTRAL, neutral),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = actionGap },
            )
        }
        val row =
            LinearLayout(uiContext).apply {
                orientation =
                    if (uiContext.resources.configuration.fontScale > 1.3f) {
                        LinearLayout.VERTICAL
                    } else {
                        LinearLayout.HORIZONTAL
                    }
            }
        listOf(BUTTON_NEGATIVE, BUTTON_POSITIVE).forEach { which ->
            content.actions[which]?.let { value ->
                val params =
                    if (row.orientation == LinearLayout.HORIZONTAL) {
                        LinearLayout.LayoutParams(0, -2, 1f)
                    } else {
                        LinearLayout.LayoutParams(-1, -2)
                    }
                if (row.childCount > 0) {
                    if (row.orientation == LinearLayout.HORIZONTAL) {
                        params.marginStart = actionGap
                    } else {
                        params.topMargin = actionGap
                    }
                }
                row.addView(action(which, value), params)
            }
        }
        binding.dialogFooter.addView(row)
    }

    /** 选项列表：adapter 复用 `galaxy_ui_dialog_choice.xml`，禁用项不响应点击。 */
    private fun choiceList(items: Array<out CharSequence>): ListView {
        val height =
            dp(
                (
                    72 *
                        uiContext.resources.configuration.fontScale
                            .coerceAtLeast(1f)
                ).toInt(),
            )
        return ListView(uiContext).apply {
            choices = this
            divider = ColorDrawable(uiContext.getColor(R.color.galaxy_ui_control))
            dividerHeight = dp(1)
            choiceMode = if (content.singleChoice) ListView.CHOICE_MODE_SINGLE else ListView.CHOICE_MODE_NONE
            adapter =
                object : BaseAdapter() {
                    override fun getCount() = items.size

                    override fun getItem(position: Int) = items[position]

                    override fun getItemId(position: Int) = position.toLong()

                    override fun hasStableIds() = true

                    override fun areAllItemsEnabled() = content.itemEnabled == null

                    override fun isEnabled(position: Int) = content.itemEnabled?.invoke(position) != false

                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view =
                            convertView as? CheckedTextView
                                ?: LayoutInflater.from(uiContext).inflate(
                                    R.layout.galaxy_ui_dialog_choice,
                                    parent,
                                    false,
                                ) as CheckedTextView
                        view.text = items[position]
                        view.minHeight = height
                        view.layoutParams = AbsListView.LayoutParams(-1, height)
                        view.isEnabled = content.itemEnabled?.invoke(position) != false
                        GalaxyStyle.text(
                            view,
                            if (view.isEnabled) R.color.galaxy_ui_text else R.color.galaxy_ui_disabled,
                        )
                        if (content.singleChoice) {
                            view.checkMarkDrawable =
                                StateListDrawable().apply {
                                    addState(
                                        intArrayOf(android.R.attr.state_checked),
                                        uiContext.getDrawable(R.drawable.galaxy_ui_radio_on),
                                    )
                                    addState(intArrayOf(), uiContext.getDrawable(R.drawable.galaxy_ui_radio_off))
                                }
                            view.checkMarkDrawable?.alpha = if (view.isEnabled) 255 else 80
                        }
                        return view
                    }
                }
            if (content.singleChoice && content.checked in items.indices) setItemChecked(content.checked, true)
            setOnItemClickListener { _, _, index, _ ->
                if (content.itemEnabled?.invoke(index) == false) return@setOnItemClickListener
                content.itemClick?.onClick(this@GalaxyModalDialog, index)
                if (!content.singleChoice) dismiss()
            }
            layoutParams = LinearLayout.LayoutParams(-1, height * items.size + dp(items.size))
        }
    }

    /** 操作按钮：点击后先禁用再回调，避免重复提交。 */
    private fun action(which: Int, value: GalaxyDialogs.Action) =
        GalaxyViews
            .actionButton(
                uiContext,
                value.label.toString(),
                primary = which == BUTTON_POSITIVE,
            ) {
                actions[which]?.isEnabled = false
                value.click?.onClick(this, which)
                dismiss()
            }.also {
                actions[which] = it
                GalaxyStyle.button(it, primary = which == BUTTON_POSITIVE, radius = 4, decorate = content.decorate)
            }

    /** 逐层给嵌入内容套用输入/文字样式；已绑定样式的按钮保留主次外观。 */
    private fun styleInputs(view: View) {
        if (view is EditText) {
            GalaxyStyle.input(view)
        } else if (view is Button) {
            if (!GalaxyStyle.isBound(view)) GalaxyStyle.button(view)
        } else if (view is TextView && !GalaxyStyle.isBound(view)) {
            view.textSize = 18f
            GalaxyStyle.text(
                view,
                if (view.currentTextColor == uiContext.getColor(R.color.galaxy_ui_warning)) {
                    R.color.galaxy_ui_warning
                } else {
                    R.color.galaxy_ui_muted
                },
            )
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) styleInputs(view.getChildAt(index))
    }

    private fun dp(value: Int) = GalaxyViews.dp(uiContext, value)

    /** 操作按钮之间的间距；与样式资源保持同一数值来源。 */
    private val actionGap get() = GalaxyViews.px(uiContext, R.dimen.galaxy_ui_dialog_action_gap)
}
