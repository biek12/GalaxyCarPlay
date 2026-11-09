package com.galaxy.autorecord.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.galaxy.autorecord.core.RecordingState

/**
 * 录制操作条（v1.1 §2.2/§2.3-7，替代原稿的"长按徽标"）。
 *
 * 为什么改：长按是隐藏手势，行车场景不可发现；而"停止录制"是不可逆的中断动作，
 * 不能靠误触触发。所以徽标点击 → 弹出操作条 → 危险动作二次确认。
 */
internal object RecordActionSheet {

    fun show(host: RecordActivity) {
        val ui = host.ui
        val state = host.controller.snapshot.state
        val body = ui.column().apply {
            background = ui.rounded(ui.surface, 12)
            setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(16))
        }

        val title = ui.text("录制操作", RecordRole.SECTION_TITLE).apply {
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8))
        }
        body.addView(title, LinearLayout.LayoutParams(-1, -2))

        val dialog = Dialog(host)
        fun dismiss() = dialog.dismiss()

        if (state == RecordingState.RECORDING || state == RecordingState.STARTING) {
            body.addView(item(ui, "停止录制", "结束当前片段并保存，需二次确认") {
                dismiss()
                RecordDialogs.confirm(host, "停止录制", "将结束当前片段并保存，确定停止？", "停止") {
                    host.requestStopRecording()
                }
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            body.addView(item(ui, "开始录制", "先做预检：四路通道、编码器、存储、引擎") {
                dismiss()
                host.requestStartRecording()
            }, LinearLayout.LayoutParams(-1, -2))
        }

        body.addView(item(ui, "锁定最近片段", "锁定当前段 + 最近一条已完成段，防止被循环覆盖") {
            dismiss()
            host.toastLine(host.lockRecent())
        }, LinearLayout.LayoutParams(-1, -2))

        body.addView(item(ui, "打开实时画面", "回到四宫格与车辆数据") {
            dismiss()
            host.openMainPage(MainPage.LIVE)
        }, LinearLayout.LayoutParams(-1, -2))

        if (state == RecordingState.ERROR) {
            body.addView(item(ui, "清除异常状态", "仅清除界面状态，不会开始录制") {
                dismiss()
                host.clearRecordingError()
            }, LinearLayout.LayoutParams(-1, -2))
        }

        dialog.setContentView(body)
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun item(ui: RecordUi, title: String, description: String, onClick: () -> Unit): View {
        val row = ui.column().apply {
            minimumHeight = ui.dp(80)
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ui.rippleColor), null, null,
            )
        }
        row.addView(ui.text(title, RecordRole.LABEL), LinearLayout.LayoutParams(-1, -2))
        row.addView(ui.text(description, RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-1, -2))
        return row
    }
}

/** 确认弹窗（危险动作必须二次确认，规格 §2.3-7）。 */
internal object RecordDialogs {
    fun confirm(host: RecordActivity, title: String, message: String, positive: String, onConfirm: () -> Unit) {
        android.app.AlertDialog.Builder(host)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .show()
    }
}
