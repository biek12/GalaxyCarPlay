package com.galaxy.autorecord.ui

import android.view.View
import android.widget.ScrollView

/** 一级页（导航栏三个入口）。 */
internal enum class MainPage(val label: String, val glyph: String) {
    LIVE("实时画面", "live"),
    LIBRARY("录像库", "library"),
    SETTINGS("设置", "settings"),
}

/** 设置二级页。本轮只有 P6/P7 真实实现，其余按规格给"待接入"占位并可返回。 */
internal enum class SubPage(val label: String, val subtitle: String, val glyph: String) {
    STORAGE("存储与保存", "存储位置、配额、循环覆盖、分段", "storage"),
    CODEC("编码与画质", "视频格式、硬/软编码、分辨率、帧率、码率", "encode"),
    RECORDING("录制与后台", "自动录制、后台与息屏、关闭联动", "record"),
    VEHICLE("车辆数据与事件", "订阅频率、记录项、事件规则", "vehicle"),
    PLAYBACK("回放与显示", "默认布局、倍速、事件轨道、日夜", "playback"),
    DIAGNOSE("诊断", "能力探测、编码器实测、存储自检、日志", "diagnose"),
    ABOUT("关于", "版本、契约、许可", "about"),
}

/**
 * 页面基类。
 *
 * 页面只持有自己的 View，不直接改 Activity 的窗口结构；导航、标题栏、返回栈由
 * [RecordActivity] 统一处理，这样切换页面不会重建录制相关对象（规格 §2.3 第 1 条）。
 * View 只构建一次并缓存，切页/返回时滚动位置天然保留。
 */
internal abstract class RecordPage(protected val activity: RecordActivity, protected val ui: RecordUi) {
    abstract val title: String

    protected var root: View? = null
        private set
    private var built = false

    fun view(): View {
        if (!built) {
            root = build()
            built = true
        }
        return root ?: throw IllegalStateException("页面未构建")
    }

    protected abstract fun build(): View

    /** 每次切到该页时调用：只刷新显示值，不重建控件、不重开任何会话。 */
    open fun onShown() {}

    /** 页面被换下（切页/退到后台）：这里释放预览等只跟可见性有关的资源。 */
    open fun onHidden() {}

    /** 摄像头出帧（已在主线程）。只有需要显示画面的页面关心。 */
    open fun onCameraFrame(direction: String) {}

    /** 每秒节拍（录制时长、片段进度、时钟）。页面只更新文字，不做耗时操作。 */
    open fun onTick() {}

    /** 录制快照变化（开始/停止/异常）。 */
    open fun onSnapshotChanged() {}

    /** 数据变化（库扫描完成、设置改动、卷变化、车辆数据更新）。 */
    open fun onDataChanged() {}

    /**
     * 页内一次性提示（替代 Toast）：错误与结果必须留在页面上可读，
     * 不允许用一闪而过的浮层冒充反馈（规格 §2.5）。
     */
    open fun showTransient(message: String) {}

    /** 页面内返回（如未来的单路放大）；返回 true 表示已消费返回事件。 */
    open fun onBack(): Boolean = false

    open fun scrollY(): Int = (root as? ScrollView)?.scrollY ?: 0

    open fun restoreScroll(y: Int) {
        (root as? ScrollView)?.scrollTo(0, y)
    }

    /** 标题栏右侧自适应槽内容（徽标）。每天页面各自决定放什么，不放就返回 null。 */
    open fun rightSlot(container: android.widget.LinearLayout) {}
}