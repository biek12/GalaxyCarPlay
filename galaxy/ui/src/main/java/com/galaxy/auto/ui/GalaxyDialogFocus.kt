package com.galaxy.auto.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * 键盘操作关闭弹窗后恢复入口焦点；触屏操作不强制退出触摸模式或留下焦点高亮。
 *
 * 构造时快照打开弹窗前的焦点与触摸模式，[restore] 在窗口停止后投递一次恢复任务。
 * 线程：构造与恢复均为主线程；宿主已结束或再次进入触摸模式时不做任何操作。
 */
class GalaxyDialogFocus(
    context: Context,
) {
    private val activity = context as? Activity
    private val opener = activity?.window?.decorView?.findFocus()
    private val keyboardNavigation = activity?.window?.decorView?.isInTouchMode == false
    private val rowTitle = (opener as? GalaxySettingRow)?.titleView?.text?.toString()

    /** 恢复焦点；入口视图已不可用时按行标题找回同一设置行。 */
    fun restore() {
        val owner = activity ?: return
        if (!keyboardNavigation) return
        owner.window.decorView.post {
            if (owner.isFinishing || owner.isDestroyed || owner.window.decorView.isInTouchMode) return@post
            val target =
                opener?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled }
                    ?: rowTitle?.let { findRow(owner.window.decorView, it) }
            target?.takeIf { it.isShown && it.isEnabled }?.requestFocus()
        }
    }

    /** 在视图树中按标题查找设置行；找不到时返回 null。 */
    private fun findRow(view: View, title: String): View? {
        if (view is GalaxySettingRow && view.titleView.text.toString() == title) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findRow(view.getChildAt(index), title)?.let { return it }
            }
        }
        return null
    }
}
