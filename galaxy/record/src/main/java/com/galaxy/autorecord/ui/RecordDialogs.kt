package com.galaxy.autorecord.ui

import android.app.AlertDialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import com.galaxy.autorecord.core.VolumeInfo

/** 单选项目：不可选项必须带原因，菜单里就直接写明为什么不能选（不静默隐藏）。 */
internal data class ChoiceOption(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val reason: String = "",
)

/**
 * 单选弹窗：只在点选项时提交，取消不改设置。
 * 未通过能力校验的选项保留在列表里并给出原因，避免用户以为"没有这个功能"。
 */
internal fun RecordUi.choiceDialog(
    title: String,
    options: List<ChoiceOption>,
    currentId: String?,
    onPick: (String) -> Unit,
) {
    val body = column().apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }
    val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setView(ScrollView(context).apply { addView(body) })
        .setNegativeButton("取消", null)
        .create()
    options.forEach { option ->
        val row = row().apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            minimumHeight = dp(64)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            if (option.enabled) {
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor), null, null,
                )
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    onPick(option.id)
                }
            }
        }
        val label = text(option.label, RecordRole.LABEL).apply {
            setTextColor(if (option.enabled) textColor else disabled)
        }
        row.addView(label, LinearLayout.LayoutParams(-1, -2))
        if (option.reason.isNotEmpty()) {
            row.addView(text(option.reason, RecordRole.DESCRIPTION).apply {
                setTextColor(if (option.enabled) muted else warning)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(row, LinearLayout.LayoutParams(-1, -2))
        if (option.id == currentId) body.addView(text("当前设置", RecordRole.DESCRIPTION).apply {
            setTextColor(accent)
            setPadding(dp(20), 0, dp(20), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
    }
    dialog.show()
}

/** 卷状态用语（规格 §2.5）；"已移除"与"只读"、"不可写"必须分开说。 */
internal fun volumeStatus(volume: VolumeInfo): String = when {
    !volume.mounted -> "已移除"
    volume.appDir == null -> "不可写（App 在本卷没有目录）"
    !volume.writable -> "只读"
    else -> "可写"
}

/**
 * 存储位置选择弹窗：列出内置与各可插拔卷的名称/剩余/总量/状态，并提供"刷新"重新枚举。
 * 这里只做选择，不在这里写任何设置：真写探测与保存由调用方在选中后执行，
 * 探测失败必须保持原值，所以不能先改后验。
 */
internal fun RecordUi.volumeDialog(
    volumes: List<VolumeInfo>,
    currentId: String?,
    loadError: String?,
    onRefresh: () -> Unit,
    onPick: (VolumeInfo) -> Unit,
) {
    val body = column().apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }
    val dialog = AlertDialog.Builder(context)
        .setTitle("选择存储位置")
        .setView(ScrollView(context).apply { addView(body) })
        .setNegativeButton("取消", null)
        .setNeutralButton("刷新", null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialog.dismiss()
            onRefresh()
        }
    }
    if (loadError != null) {
        body.addView(text(loadError, RecordRole.FEEDBACK).apply {
            setTextColor(danger)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
    }
    if (volumes.isEmpty()) {
        body.addView(text("当前没有可列出的存储卷（尚未取得结果或枚举失败）", RecordRole.DESCRIPTION).apply {
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
    }
    volumes.forEach { volume ->
        val selectable = volume.mounted && volume.appDir != null
        val row = row().apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            minimumHeight = dp(80)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            if (selectable) {
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor), null, null,
                )
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    onPick(volume)
                }
            }
        }
        val selectedMark = if (volume.id == currentId) "（当前）" else ""
        row.addView(text(volume.label + selectedMark, RecordRole.LABEL).apply {
            setTextColor(if (selectable) textColor else disabled)
        }, LinearLayout.LayoutParams(-1, -2))
        row.addView(text(
            "剩余 ${RecordFmt.bytes(volume.freeBytes)} / 共 ${RecordFmt.bytes(volume.totalBytes)} · ${volumeStatus(volume)}",
            RecordRole.DESCRIPTION,
        ).apply {
            setTextColor(if (volume.mounted) muted else warning)
        }, LinearLayout.LayoutParams(-1, -2))
        volume.appDir?.let { dir ->
            row.addView(text(shortPath(dir.absolutePath), RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(row, LinearLayout.LayoutParams(-1, -2))
        body.addView(divider(), LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)))
    }
    dialog.show()
}

/** 路径简写：只显示末两段，避免弹窗里出现完整绝对路径。 */
internal fun shortPath(path: String): String {
    val parts = path.trimEnd('/').split('/')
    return if (parts.size <= 2) path else "…/" + parts.takeLast(2).joinToString("/")
}

/** 纯信息弹窗（用于"该页待接入"等一次性说明）。 */
internal fun RecordUi.infoDialog(title: String, message: String) {
    val body = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
    body.addView(text(message, RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-1, -2))
    AlertDialog.Builder(context)
        .setTitle(title)
        .setView(ScrollView(context).apply { addView(body) })
        .setPositiveButton("知道了", null)
        .create()
        .show()
}
