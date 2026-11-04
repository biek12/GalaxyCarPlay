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
 * 片段缩略图：真实存在 `thumb.jpg` 就显示它；否则画 2×2 四向占位格并标明缺哪一路。
 *
 * 纪律：缺路格写"缺"，**不借用其他方向的画面**填满（需求稿 R01-5、规格 §5 P2）。
 */
internal fun RecordUi.thumbView(entry: SegmentEntry): View {
    val frame = Aspect16x9(context).apply {
        background = rounded(control, 4).apply { setStroke(dp(1).coerceAtLeast(1), border) }
        clipToOutline = true
    }
    val thumbFile = File(entry.dir, SegmentLayout.FILE_THUMB)
    val bitmap: Bitmap? = if (thumbFile.exists()) {
        runCatching { BitmapFactory.decodeFile(thumbFile.absolutePath) }.getOrNull()
    } else null

    if (bitmap != null) {
        frame.addView(
            android.widget.ImageView(context).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bitmap)
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        return frame
    }

    val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    SegmentLayout.DIRECTIONS.chunked(2).forEach { pair ->
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        pair.forEach { direction ->
            val missing = direction in entry.missingDirections
            val cell = FrameLayout(context).apply {
                setBackgroundColor(if (missing) warning else control)
            }
            cell.addView(
                text(if (missing) "缺" else directionLabel(direction), RecordRole.OVERLAY).apply {
                    setTextColor(if (missing) primaryText else muted)
                },
                FrameLayout.LayoutParams(-2, -2, Gravity.CENTER),
            )
            row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    frame.addView(grid, FrameLayout.LayoutParams(-1, -1))
    return frame
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
    val body = column().apply { setPadding(dp(8), dp(8), dp(8), dp(10)) }
    card.addView(body, LinearLayout.LayoutParams(-1, -2))

    body.addView(thumbView(entry), LinearLayout.LayoutParams(-1, -2))

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

    body.addView(
        text("${entry.volumeLabel} · ${RecordFmt.bytes(entry.bytes)}", RecordRole.DESCRIPTION),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
    )

    val footer = row()
    footer.addView(column(), LinearLayout.LayoutParams(0, -2, 1f))
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

/**
 * 多轨事件时间轴（P3/P4 共用）。
 *
 * 为什么自绘而不是堆 View：6 条轨道 × N 个事件，用 View 会变成几百个控件；
 * 自绘一次 draw 完成，拖动游标也不掉帧。
 *
 * 交互：点轨道任意位置 → 回调目标进度（0..1），由页面统一 seek 四路 + 数据条 + 游标。
 */
internal class EventTimelineView(
    context: Context,
    private val ui: RecordUi,
    private val onSeek: (Float) -> Unit,
) : View(context) {

    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val diamond = Path()

    var events: List<EventMark> = emptyList()
        set(value) { field = value; invalidate() }

    var durationMs: Long = 0L
        set(value) { field = value; invalidate() }

    var positionMs: Long = 0L
        set(value) { field = value; invalidate() }

    /** 类型筛选：空集表示全部显示。 */
    var visibleKinds: Set<EventKind> = emptySet()
        set(value) { field = value; invalidate() }

    private val labelWidth get() = ui.dp(64)
    private val laneHeight get() = ui.dp(26)
    private val verticalPadding get() = ui.dp(8)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = lanes(EventKind.entries.toList()) * laneHeight + verticalPadding * 2
        setMeasuredDimension(width, height)
    }

    private fun lanes(kinds: List<EventKind>): Int = kinds.size

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val kinds = EventKind.entries.toList()
        canvas.save()
        canvas.translate(labelWidth.toFloat(), verticalPadding.toFloat())
        val trackWidth = (width - labelWidth - ui.dp(12)).coerceAtLeast(1)
        val trackLeft = 0f
        val trackTop = 0f

        kinds.forEachIndexed { index, kind ->
            val top = trackTop + index * laneHeight
            val centerY = top + laneHeight / 2f
            lanePaint.color = ui.dividerColor
            lanePaint.strokeWidth = 1f
            canvas.drawLine(trackLeft, centerY, trackLeft + trackWidth, centerY, lanePaint)

            if (visibleKinds.isEmpty() || kind in visibleKinds) {
                val color = kindColor(kind)
                events.filter { it.kind == kind }.forEach { event ->
                    if (durationMs <= 0L) return@forEach
                    val startFraction = (event.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    val endFraction = (event.endMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    bandPaint.color = color
                    if (event.isDiscrete) {
                        val cx = trackLeft + trackWidth * startFraction
                        diamond.reset()
                        diamond.moveTo(cx, centerY - ui.dp(5))
                        diamond.lineTo(cx + ui.dp(5), centerY)
                        diamond.lineTo(cx, centerY + ui.dp(5))
                        diamond.lineTo(cx - ui.dp(5), centerY)
                        diamond.close()
                        canvas.drawPath(diamond, bandPaint)
                    } else {
                        val left = trackLeft + trackWidth * startFraction
                        val right = (trackLeft + trackWidth * endFraction).coerceAtLeast(left + ui.dp(2))
                        canvas.drawRoundRect(
                            RectF(left, centerY - ui.dp(4), right, centerY + ui.dp(4)),
                            ui.dp(2).toFloat(), ui.dp(2).toFloat(), bandPaint,
                        )
                    }
                }
            }

            textPaint.color = ui.muted
            canvas.drawText(
                kind.label,
                -labelWidth.toFloat() + ui.dp(4),
                centerY + textPaint.textSize / 3f,
                textPaint,
            )
        }
        canvas.restore()

        if (durationMs > 0L) {
            val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val x = labelWidth + (width - labelWidth - ui.dp(12)) * fraction
            playheadPaint.color = ui.accent
            canvas.drawLine(x, 0f, x, height.toFloat(), playheadPaint)
        }
    }

    private fun kindColor(kind: EventKind): Int = when (kind) {
        EventKind.BRAKE -> ui.danger
        EventKind.ACCEL -> ui.connected
        EventKind.ADAS_ON, EventKind.ADAS_OFF -> ui.accent
        EventKind.CRUISE_ON, EventKind.CRUISE_OFF -> ui.warning
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && durationMs > 0L) {
            val trackWidth = (width - labelWidth - ui.dp(12)).coerceAtLeast(1)
            val fraction = ((event.x - labelWidth) / trackWidth).coerceIn(0f, 1f)
            onSeek(fraction)
            performClick()
            return true
        }
        return event.action == MotionEvent.ACTION_DOWN
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** 片段完整度的展示色，三处（卡片/详情/回放）统一。 */
internal fun RecordUi.completenessColor(entry: SegmentEntry): Int = when (entry.completeness) {
    SegmentCompleteness.COMPLETE -> muted
    SegmentCompleteness.WRITING -> warning
    else -> danger
}
