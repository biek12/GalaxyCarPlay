package com.galaxy.autorecord.ui

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.galaxy.autorecord.core.LibraryDateRange
import com.galaxy.autorecord.core.LibraryFilter
import com.galaxy.autorecord.core.LibraryPeriod
import com.galaxy.autorecord.core.SegmentEntry
import com.galaxy.autorecord.core.LibraryState
import com.galaxy.autorecord.core.LibraryTimeWindow

/**
 * P2 录像库（管理页，v1.1 §5 P2）。
 *
 * 三个关键区分：
 * 1. **未扫描 / 扫描失败 / 扫描成功但为空** 三种状态分别显示，只有第三种才出"还没有录像"空态；
 * 2. 筛选按用户任务（全部/重要/异常/未看），工程状态降级为角标——用户按"我要找什么"筛，不按系统分类筛；
 * 3. 组头给日期与片段数，卡片双列（内容宽 <440dp 时单列），角标最多 2 个 + `+N`。
 */
internal class LibraryPage(private val host: RecordActivity, recordUi: RecordUi) : RecordPage(host, recordUi) {
    override val title: String = "录像库"

    private lateinit var countBadge: TextView
    private lateinit var filterRow: LinearLayout
    private lateinit var moreRow: LinearLayout
    private lateinit var listHost: LinearLayout
    private lateinit var selectionBar: WrapRow
    private lateinit var moreToggle: TextView
    private lateinit var noticeView: TextView

    private var filter = LibraryFilter.ALL
    private var dateRange = LibraryDateRange.ALL
    private var period = LibraryPeriod.ALL
    private var showMore = false
    private var selecting=false
    private val selected = linkedSetOf<String>()
    private var renderedState: LibraryState? = null
    private var visibleCount = 12
    private var fromMillis: Long? = null
    private var untilMillis: Long? = null

    override fun build(): View = ui.pageScroll { column ->
        filterRow = ui.row()
        column.addView(filterRow, LinearLayout.LayoutParams(-1, -2))
        renderFilters()

        moreToggle = ui.chip("更多筛选", false) {
            showMore = !showMore
            renderFilters()
        }.apply {
            compoundDrawablePadding = ui.dp(8)
            setCompoundDrawables(ui.glyphDrawable("filter", ui.textColor).apply {
                setBounds(0, 0, ui.dp(22), ui.dp(22))
            }, null, null, null)
        }
        filterRow.addView(moreToggle, LinearLayout.LayoutParams(-2, ui.dp(44)))

        moreRow = ui.column()
        column.addView(moreRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })

        selectionBar = WrapRow(ui.context,ui.dp(8),ui.dp(4)).apply { visibility = View.GONE }
        column.addView(selectionBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })

        noticeView = ui.note("").apply { visibility = View.GONE }
        column.addView(noticeView, LinearLayout.LayoutParams(-1, -2))

        listHost = ui.column()
        column.addView(listHost, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        renderList()
    }

    // ---- 筛选 ----

    private fun renderFilters() {
        filterRow.removeAllViews()
        LibraryFilter.entries.forEach { option ->
            val chip = ui.chip(option.label, filter == option) {
                filter = option
                visibleCount = 12
                selected.clear()
                renderFilters()
                renderList()
                renderSelectionBar()
            }
            filterRow.addView(chip, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginEnd = ui.dp(8) })
        }
        if (::moreToggle.isInitialized) {
            moreToggle.text = if (showMore) "收起筛选" else "更多筛选"
            filterRow.addView(moreToggle, LinearLayout.LayoutParams(-2, ui.dp(44)))
        }
        filterRow.addView(ui.chip(if(selecting)"退出多选"else"选择",selecting){
            selecting=!selecting;if(!selecting)selected.clear();renderFilters();renderList();renderSelectionBar()
        },LinearLayout.LayoutParams(-2,ui.dp(44)).apply{marginStart=ui.dp(8)})
        renderMore()
    }

    private fun renderMore() {
        if (!::moreRow.isInitialized) return
        moreRow.removeAllViews()
        moreRow.visibility = if (showMore) View.VISIBLE else View.GONE
        if (!showMore) return

        val custom = ui.row()
        custom.addView(ui.chip("开始："+(fromMillis?.let(RecordTime::wallTime) ?: "不限"), false) {
            pickDateTime(true)
        },LinearLayout.LayoutParams(0,ui.dp(48),1f))
        custom.addView(ui.chip("结束："+(untilMillis?.let(RecordTime::wallTime) ?: "不限"), false) {
            pickDateTime(false)
        },LinearLayout.LayoutParams(0,ui.dp(48),1f))
        moreRow.addView(custom,LinearLayout.LayoutParams(-1,-2))
        moreRow.addView(ui.note("按录像与所选时间段的交集筛选，跨越开始时间的片段也会列出。"),LinearLayout.LayoutParams(-1,-2))

        moreRow.addView(ui.text("日期", RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        val dateRow = WrapRow(ui.context, ui.dp(8), ui.dp(8))
        LibraryDateRange.entries.forEach { option ->
            dateRow.addView(ui.chip(option.label, dateRange == option) {
                dateRange = option
                visibleCount = 12
                renderMore()
                renderList()
            }, LinearLayout.LayoutParams(-2, ui.dp(44)))
        }
        moreRow.addView(dateRow, LinearLayout.LayoutParams(-1, -2))

        moreRow.addView(ui.text("时段", RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val periodRow = WrapRow(ui.context, ui.dp(8), ui.dp(8))
        LibraryPeriod.entries.forEach { option ->
            periodRow.addView(ui.chip(option.label, period == option) {
                period = option
                visibleCount = 12
                renderMore()
                renderList()
            }, LinearLayout.LayoutParams(-2, ui.dp(44)))
        }
        moreRow.addView(periodRow, LinearLayout.LayoutParams(-1, -2))

        moreRow.addView(
            ui.note("「类别」（手动/自动/异常）需要录制侧在清单里写入录制方式，B3 之后开放；当前不放出来，避免筛了却筛不准。"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) },
        )

        val active = dateRange != LibraryDateRange.ALL || period != LibraryPeriod.ALL || filter != LibraryFilter.ALL || fromMillis!=null || untilMillis!=null
        if (active) {
            moreRow.addView(ui.chip("清除全部筛选", false) {
                filter = LibraryFilter.ALL
                fromMillis=null;untilMillis=null
                visibleCount = 12
                dateRange = LibraryDateRange.ALL
                period = LibraryPeriod.ALL
                renderFilters()
                renderList()
            }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { topMargin = ui.dp(8) })
        }
    }

    // ---- 列表 ----

    private fun renderList() {
        if (!::listHost.isInitialized) return
        listHost.removeAllViews()
        val state = host.library
        selected.retainAll(state.segments.map{it.id}.toSet())
        renderedState = state

        when {
            !state.scanned -> {
                listHost.addView(hintCard("正在读取本地索引…", "首次进入会扫描段目录，完成后显示片段数。"))
                return
            }
            state.error != null -> {
                listHost.addView(
                    hintCard("无法列出片段", "${state.error}\n\n这不是「没有录像」：读取失败与空库必须分开看。"),
                )
                return
            }
            state.segments.isEmpty() -> {
                listHost.addView(emptyCard(state.rootExists))
                return
            }
        }

        val entries = state.query(filter, dateRange, period, window=LibraryTimeWindow(fromMillis,untilMillis))
        if (entries.isEmpty()) {
            listHost.addView(
                hintCard("没有匹配的片段", "当前筛选下没有结果。片段总数 ${state.segments.size} 段，可清除筛选查看全部。"),
            )
            return
        }

        val twoColumns = ui.context.resources.displayMetrics.widthPixels / ui.context.resources.displayMetrics.density >= 604f
        state.groupByDay(entries.take(visibleCount)).forEach { group ->
            val header = ui.row()
            header.addView(ui.sectionTitle(group.label), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(ui.text("${group.segments.size} 段", RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-2, -2))
            listHost.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })

            group.segments.chunked(if (twoColumns) 2 else 1).forEach { pair ->
                val row = ui.row().apply { gravity = Gravity.TOP }
                pair.forEachIndexed { index, entry ->
                    if (index > 0) row.addView(View(ui.context), LinearLayout.LayoutParams(ui.dp(12), 1))
                    val card = ui.segmentCard(
                        entry = entry,
                        selected = entry.id in selected,
                        onOpen = {
                            if (selecting || selected.isNotEmpty()) {
                                toggleSelection(entry)
                            } else {
                                host.markPlayed(entry)
                                host.openPlayback(entry.id)
                            }
                        },
                        onMore = { SegmentActions.show(host, entry) },
                    )
                    card.setOnLongClickListener {
                        toggleSelection(entry)
                        true
                    }
                    row.addView(card, LinearLayout.LayoutParams(0, -2, 1f))
                }
                if (pair.size == 1) row.addView(ui.column(), LinearLayout.LayoutParams(0, 1, 1f))
                listHost.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            }
        }
        if (entries.size > visibleCount) {
            listHost.addView(ui.secondaryButton("加载更多（$visibleCount/${entries.size}）") {
                visibleCount += 12
                renderList()
            }, LinearLayout.LayoutParams(-1, ui.dp(48)).apply { topMargin = ui.dp(12) })
        }
        renderSelectionBar()
    }

    private fun toggleSelection(entry: SegmentEntry) {
        selecting=true
        if (entry.id in selected) selected.remove(entry.id) else selected += entry.id
        renderList()
        renderSelectionBar()
    }

    private fun renderSelectionBar() {
        if (!::selectionBar.isInitialized) return
        selectionBar.removeAllViews()
        selectionBar.visibility = if (!selecting && selected.isEmpty()) View.GONE else View.VISIBLE
        if (!selecting && selected.isEmpty()) return
        selectionBar.addView(
            ui.text("已选 ${selected.size}", RecordRole.LABEL),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        selectionBar.addView(ui.chip("全选筛选结果",false){
            selecting=true
            selected.addAll(host.library.query(filter,dateRange,period,window=LibraryTimeWindow(fromMillis,untilMillis)).map{it.id})
            renderList();renderSelectionBar()
        },LinearLayout.LayoutParams(-2,ui.dp(44)).apply{marginEnd=ui.dp(8)})
        selectionBar.addView(ui.chip("锁定", false) {
            val marks = host.library.segments.filter { it.id in selected }
            selected.clear();renderList();showTransient("正在锁定…")
            host.lockSegments(marks,true){showTransient(it)}
        }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginEnd = ui.dp(8) })
        selectionBar.addView(ui.chip("解锁", false) {
            val marks = host.library.segments.filter { it.id in selected }
            selected.clear();renderList();showTransient("正在解锁…")
            host.lockSegments(marks,false){showTransient(it)}
        }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginEnd = ui.dp(8) })
        selectionBar.addView(ui.chip("删除", false) {
            val targets = host.library.segments.filter { it.id in selected }
            if(targets.isEmpty()){showTransient("请先选择要删除的片段");return@chip}
            RecordDialogs.confirm(
                host, "删除 ${targets.size} 段",
                "将同时删除四路视频、车辆数据与索引项，不可恢复。锁定片段不会删除；含事件的未锁定片段也会在本次确认后删除。",
                "删除",
            ) {
                selected.clear()
                renderSelectionBar()
                showTransient("正在删除…")
                host.deleteSegments(targets) { showTransient(it) }
            }
        }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginEnd = ui.dp(8) })
        selectionBar.addView(ui.chip("取消", false) {
            selecting=false
            selected.clear()
            renderFilters()
            renderList()
            renderSelectionBar()
        }, LinearLayout.LayoutParams(-2, ui.dp(44)))
    }

    private fun pickDateTime(start: Boolean) {
        val value=java.util.Calendar.getInstance().apply { timeInMillis=(if(start)fromMillis else untilMillis)?:System.currentTimeMillis() }
        android.app.DatePickerDialog(host,{_,year,month,day->
            value.set(year,month,day)
            android.app.TimePickerDialog(host,{_,hour,minute->
                value.set(java.util.Calendar.HOUR_OF_DAY,hour);value.set(java.util.Calendar.MINUTE,minute)
                value.set(java.util.Calendar.SECOND,0);value.set(java.util.Calendar.MILLISECOND,0)
                val from=if(start)value.timeInMillis else fromMillis
                val until=if(start)untilMillis else value.timeInMillis
                if(from!=null&&until!=null&&from>=until){showTransient("结束时间必须晚于开始时间");return@TimePickerDialog}
                fromMillis=from;untilMillis=until;selected.clear();visibleCount=12
                renderMore();renderList()
            },value.get(java.util.Calendar.HOUR_OF_DAY),value.get(java.util.Calendar.MINUTE),true).show()
        },value.get(java.util.Calendar.YEAR),value.get(java.util.Calendar.MONTH),value.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }

    private fun hintCard(title: String, message: String): View {
        val card = ui.card()
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16)) }
        card.addView(body, LinearLayout.LayoutParams(-1, -2))
        body.addView(ui.text(title, RecordRole.LABEL), LinearLayout.LayoutParams(-1, -2))
        body.addView(ui.text(message, RecordRole.DESCRIPTION), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = ui.dp(6)
        })
        return card
    }

    /** 只有"扫描成功且确实 0 段"才走这里，并区分"还没录过"与"介质上还没有本应用目录"。 */
    private fun emptyCard(rootExists: Boolean): View {
        val card = ui.card()
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20)) }
        card.addView(body, LinearLayout.LayoutParams(-1, -2))
        body.addView(ui.text("还没有录像", RecordRole.LABEL), LinearLayout.LayoutParams(-1, -2))
        body.addView(
            ui.text(
                if (rootExists) "当前存储位置下没有已收尾的片段。开始录制后，每个片段会出现在这里。"
                else "当前存储位置下还没有本应用的录像目录（首次录制时创建）。",
                RecordRole.DESCRIPTION,
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) },
        )
        val row = ui.row()
        row.addView(ui.primaryButton("去实时画面") { host.openMainPage(MainPage.LIVE) }, LinearLayout.LayoutParams(0, ui.dp(72), 1f))
        row.addView(ui.secondaryButton("存储设置") { host.openSubPage(SubPage.STORAGE) }, LinearLayout.LayoutParams(-2, ui.dp(48)).apply {
            marginStart = ui.dp(12)
        })
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        return card
    }

    override fun rightSlot(container: LinearLayout) {
        // 未扫描时显示"—"而不是 0：0 会被读成"确实没有录像"。
        countBadge = ui.badge(if (host.library.scanned) "片段 ${host.library.segments.size}" else "片段 —", ui.muted)
        container.addView(countBadge, LinearLayout.LayoutParams(-2, -2))
    }

    override fun onShown() {
        if (::countBadge.isInitialized) {
            countBadge.text = if (host.library.scanned) "片段 ${host.library.segments.size}" else "片段 —"
        }
        if (renderedState !== host.library) renderList()
        host.refreshLibrary()
    }

    override fun onDataChanged() {
        // 相机/车辆回调也会通知此入口，只有库扫描发布了新状态才重建卡片。
        if (renderedState === host.library) return
        if (::countBadge.isInitialized) countBadge.text = "片段 ${host.library.segments.size}"
        renderList()
    }

    override fun showTransient(message: String) {
        if (!::noticeView.isInitialized) return
        noticeView.text = message
        noticeView.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        noticeView.setTextColor(ui.warning)
    }
}
