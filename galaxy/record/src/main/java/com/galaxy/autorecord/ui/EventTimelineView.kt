package com.galaxy.autorecord.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.galaxy.autorecord.core.*

/** 每行的绘制和命中都使用同一份Lane布局；所有轨道、力度曲线共用段相对毫秒横轴。 */
internal class EventTimelineView(context: Context, private val ui: RecordUi, private val onSeek: (Float) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.dp(11).toFloat() }
    private data class Lane(val kind: EventKind, val label: String, val trace: VehicleTrace?, val top: Int, val height: Int)
    private var lanes = emptyList<Lane>()
    private val expanded = mutableSetOf<VehicleKey>()
    private val labelWidth get() = ui.dp(112)
    private val rowHeight get() = ui.dp(30)
    private val padding get() = ui.dp(8)
    var events: List<EventMark> = emptyList()
        set(value) { field = value; invalidate() }
    var traces: List<VehicleTrace> = emptyList()
        set(value) { field = value; layoutLanes() }
    var visibleKinds: Set<EventKind> = EventKind.entries.toSet()
        set(value) { field = value; layoutLanes() }
    var onEventSelected: ((EventMark) -> Unit)? = null
    var durationMs = 0L
        set(value) { field = value; invalidate() }
    var positionMs = 0L
        set(value) { field = value; invalidate() }

    init { contentDescription = "历史数据时间轴；点击油门或刹车行名前的箭头展开力度曲线"; layoutLanes() }

    private fun layoutLanes() {
        val result = mutableListOf<Lane>(); var y = padding
        fun add(kind: EventKind, label: String, trace: VehicleTrace?) {
            if (kind !in visibleKinds) return
            val height = rowHeight + if (trace?.key in expanded) ui.dp(72) else 0
            result += Lane(kind, label, trace, y, height); y += height
        }
        EventKind.entries.take(6).forEach { kind ->
            val trace = traces.firstOrNull { (kind == EventKind.BRAKE && it.key == VehicleKey.BRAKE) || (kind == EventKind.ACCEL && it.key == VehicleKey.ACCELERATOR) }
            add(kind, kind.label, trace)
        }
        traces.filter { !it.pedal }.forEach { add(it.kind, it.key.label, it) }
        lanes = result; requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (lanes.lastOrNull()?.let { it.top + it.height } ?: padding) + padding)
    }

    private fun x(timeMs: Long) = labelWidth + (width-labelWidth-ui.dp(12)).coerceAtLeast(1) * (timeMs.toFloat()/durationMs.coerceAtLeast(1)).coerceIn(0f,1f)
    private fun color(kind: EventKind) = when (kind) {
        EventKind.BRAKE -> ui.danger
        EventKind.ACCEL, EventKind.TURN -> ui.connected
        EventKind.ADAS_OFF, EventKind.CRUISE_OFF, EventKind.LIGHT -> ui.warning
        else -> ui.accent
    }
    private fun strengthColor(kind: EventKind, percent: Float): Int {
        val base = color(kind)
        // 同一踏板同一色系：轻踩浅，重踩深；不把颜色档位当新的事件阈值。
        val white = .65f * (1f-percent/100f)
        return Color.rgb((Color.red(base)+(255-Color.red(base))*white).toInt(),
            (Color.green(base)+(255-Color.green(base))*white).toInt(),
            (Color.blue(base)+(255-Color.blue(base))*white).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val clip = canvas.clipBounds
        lanes.forEach { lane ->
            if (lane.top + lane.height < clip.top || lane.top > clip.bottom) return@forEach
            val center = lane.top + rowHeight/2f
            paint.color = ui.dividerColor; paint.strokeWidth = 1f
            canvas.drawLine(labelWidth.toFloat(), center, (width-ui.dp(12)).toFloat(), center, paint)
            text.color = ui.muted
            val prefix = if (lane.trace?.pedal == true) { if (lane.trace.key in expanded) "▾ " else "▸ " } else ""
            canvas.drawText(prefix+lane.label, ui.dp(4).toFloat(), center+text.textSize/3, text)
            val trace = lane.trace
            if (trace != null) drawTrace(canvas, lane, trace, center)
            else events.filter { it.kind == lane.kind }.forEach { mark ->
                paint.color = color(lane.kind)
                if (mark.isDiscrete) canvas.drawCircle(x(mark.startMs), center, ui.dp(3).toFloat(), paint)
                else canvas.drawRect(x(mark.startMs), center-ui.dp(5), maxOf(x(mark.endMs),x(mark.startMs)+1f), center+ui.dp(5), paint)
            }
        }
        paint.color = ui.textColor; paint.strokeWidth = ui.dp(1).toFloat()
        canvas.drawLine(x(positionMs), 0f, x(positionMs), height.toFloat(), paint)
    }

    private fun drawTrace(canvas: Canvas, lane: Lane, trace: VehicleTrace, center: Float) {
        trace.points.forEachIndexed { index, point ->
            val next = trace.points.getOrNull(index+1)
            val joined = next != null && next.timeMs-point.timeMs <= trace.maxGapMs
            if (trace.pedal || trace.binary) {
                if (trace.active(point)) {
                    val pct = point.percent() ?: 100f
                    paint.color = if (trace.pedal) strengthColor(lane.kind,pct) else color(lane.kind)
                    // 一个有效短踩至少显示一个物理像素，不扩大其真实时间范围。
                    canvas.drawRect(x(point.timeMs),center-ui.dp(5),maxOf(x(if(joined) next!!.timeMs else point.timeMs),x(point.timeMs)+1f),center+ui.dp(5),paint)
                }
            } else if (point.value != null && (index == 0 || point.value != trace.points[index-1].value)) {
                paint.color = color(lane.kind); canvas.drawCircle(x(point.timeMs),center,ui.dp(2).toFloat(),paint)
            }
        }
        if (trace.key !in expanded) return
        val top = (lane.top+rowHeight+ui.dp(5)).toFloat(); val bottom = (lane.top+lane.height-ui.dp(8)).toFloat()
        paint.color = ui.dividerColor; canvas.drawLine(labelWidth.toFloat(),top,(width-ui.dp(12)).toFloat(),top,paint)
        canvas.drawLine(labelWidth.toFloat(),bottom,(width-ui.dp(12)).toFloat(),bottom,paint)
        text.color=ui.muted;canvas.drawText("100%",ui.dp(70).toFloat(),top+text.textSize,text);canvas.drawText("0%",ui.dp(80).toFloat(),bottom,text)
        fun y(pct: Float) = bottom-(bottom-top)*pct/100f
        trace.points.forEachIndexed { index, point ->
            val pct = point.percent() ?: return@forEachIndexed
            paint.color = strengthColor(lane.kind,pct)
            canvas.drawCircle(x(point.timeMs),y(pct),ui.dp(2).toFloat(),paint)
            val next = trace.points.getOrNull(index+1)
            val nextPct = next?.percent()
            if (next != null && nextPct != null && next.timeMs-point.timeMs <= trace.maxGapMs) {
                // 阶梯线保留观测跳变，不用平滑插值伪造中间力度。
                paint.strokeWidth=ui.dp(1).toFloat()
                canvas.drawLine(x(point.timeMs),y(pct),x(next.timeMs),y(pct),paint)
                canvas.drawLine(x(next.timeMs),y(pct),x(next.timeMs),y(nextPct),paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && durationMs>0) {
            val lane = lanes.firstOrNull { event.y >= it.top && event.y < it.top+it.height } ?: return true
            val trace = lane.trace
            if (event.x < labelWidth && trace?.pedal == true) {
                if (!expanded.add(trace.key)) expanded.remove(trace.key)
                layoutLanes(); performClick(); return true
            }
            val fraction=((event.x-labelWidth)/(width-labelWidth-ui.dp(12)).coerceAtLeast(1)).coerceIn(0f,1f)
            val at=(fraction*durationMs).toLong()
            if (trace != null) {
                val point=trace.points.lastOrNull { it.timeMs<=at }?.takeIf { at-it.timeMs <= trace.maxGapMs }
                onEventSelected?.invoke(EventMark(lane.kind,at,at,point?.percent(),"raw_trace_v1",
                    mapOf(trace.key.key to (point?.value?.let {
                        if (trace.pedal && point.percent() == null) "原始值$it（百分比量纲未核验）" else it+trace.key.unit
                    } ?: "缺失"))))
            } else events.filter { it.kind==lane.kind }.minByOrNull { kotlin.math.abs(it.startMs-at) }?.let { onEventSelected?.invoke(it) }
            onSeek(fraction); performClick(); return true
        }
        return event.action==MotionEvent.ACTION_DOWN
    }
    override fun performClick(): Boolean { super.performClick();return true }
}
