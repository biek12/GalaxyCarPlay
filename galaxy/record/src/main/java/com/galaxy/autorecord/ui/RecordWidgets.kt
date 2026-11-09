package com.galaxy.autorecord.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.galaxy.autorecord.core.EventKind
import com.galaxy.autorecord.core.EventMark
import com.galaxy.autorecord.core.SegmentCompleteness
import com.galaxy.autorecord.core.SegmentEntry
import com.galaxy.autorecord.core.SegmentLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时长/时刻格式（规格 §2.5：时长 mm:ss，≥1 小时用 h:mm:ss）。 */
internal object RecordTime {
    fun clock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun wallTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    fun dayTime(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))
}

/**
 * 片段缩略图：按需读取真实四向视频生成拼图，缺失方向保留明确占位。
 *
 * 纪律：缺路格写"缺"，**不借用其他方向的画面**填满（需求稿 R01-5、规格 §5 P2）。
 */
internal fun RecordUi.thumbView(entry: SegmentEntry): View = CameraAspectFrame(context).apply {
    setBackgroundColor(android.graphics.Color.BLACK)
    clipToOutline = true
    addView(RecordThumbnail(this@thumbView, entry), FrameLayout.LayoutParams(-1, -1))
}

/**
 * 录像库卡片（规格 §5 P2）。
 *
 * 角标最多 2 个 + `+N`：7 类角标同时挤在窄卡片上会挤爆，优先级由 core 的 [SegmentEntry.badges] 决定，
 * 界面不自己排序，避免两个地方的口径打架。
 */
internal fun RecordUi.segmentCard(
    entry: SegmentEntry,
    selected: Boolean,
    onOpen: () -> Unit,
    onMore: () -> Unit,
): View {
    val card = card()
    if (selected) {
        card.background = rounded(selectedColor, 4).apply { setStroke(dp(2).coerceAtLeast(1), accent) }
    }
    card.addView(thumbView(entry), LinearLayout.LayoutParams(-1, -2))
    val body = column().apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
    card.addView(body, LinearLayout.LayoutParams(-1, -2))

    val header = row()
    val title = text(RecordTime.dayTime(entry.startedAtMillis), RecordRole.LABEL)
    val duration = text(
        if (entry.durationMillis > 0) RecordTime.clock(entry.durationMillis) else "时长 —",
        RecordRole.DESCRIPTION,
    )
    header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
    header.addView(duration, LinearLayout.LayoutParams(-2, -2))
    body.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val badges = entry.badges()
    if (badges.isNotEmpty()) {
        val badgeRow = WrapRow(context, dp(6), dp(4)).apply { setPadding(0, dp(4), 0, 0) }
        badges.take(2).forEach { label ->
            val color = when {
                entry.completeness.isAbnormal && label.contains("缺") -> danger
                entry.completeness.isAbnormal -> warning
                entry.locked -> accent
                entry.isEventBearing -> accent
                else -> muted
            }
            badgeRow.addView(badge(label, color), LinearLayout.LayoutParams(-2, -2))
        }
        if (badges.size > 2) {
            badgeRow.addView(badge("+${badges.size - 2}", muted), LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(6)
            })
        }
        body.addView(badgeRow, LinearLayout.LayoutParams(-1, -2))
    }

    val footer = row()
    footer.addView(
        text("${entry.volumeLabel} · ${RecordFmt.bytes(entry.bytes)}", RecordRole.DESCRIPTION).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        },
        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) },
    )
    footer.addView(
        chip("详情", false) { onMore() },
        LinearLayout.LayoutParams(-2, dp(44)),
    )
    body.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    card.isClickable = true
    card.isFocusable = true
    card.contentDescription = "${RecordTime.wallTime(entry.startedAtMillis)} 的片段"
    card.setOnClickListener { onOpen() }
    return card
}

/** 片段完整度的展示色，三处（卡片/详情/回放）统一。 */
internal fun RecordUi.completenessColor(entry: SegmentEntry): Int = when (entry.completeness) {
    SegmentCompleteness.COMPLETE -> muted
    SegmentCompleteness.WRITING -> warning
    else -> danger
}
