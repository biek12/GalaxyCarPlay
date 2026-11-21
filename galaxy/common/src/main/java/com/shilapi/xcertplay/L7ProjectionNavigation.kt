package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.host.databinding.ViewL7ProjectionNavigationBinding
import kotlin.math.abs

/** 投屏上方的导航浮层；改变可见性不挤压 Surface，也不截获收起状态下的画面触控。 */
internal class L7ProjectionNavigation(
    context: Context,
    private val releaseTouches: () -> Unit,
    private val projection: Boolean = false,
    private val returnHome: () -> Unit = {},
    navigate: (String) -> Unit,
) : FrameLayout(context) {
    private val scrim: View
    private val panel: LinearLayout
    private val navigation =
        L7NavigationRail(context) { destination ->
            if (destination != "exit") collapse()
            navigate(destination)
        }
    internal val handle: ImageButton
    private var settingsPage = false
    private var connected = false
    private var floatingEnabled = L7FloatingNavigationPreferences.inAppEnabled(context)
    internal var expanded = true
        private set
    private var position = L7FloatingNavigationPreferences.position(context)
    private var downX = 0f
    private var downY = 0f
    private var originX = 0f
    private var originY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        val binding = ViewL7ProjectionNavigationBinding.inflate(LayoutInflater.from(context), this)
        scrim = binding.navigationScrim
        panel = binding.navigationPanel
        handle = binding.navigationHandle
        scrim.setOnClickListener { collapse() }
        panel.addView(navigation, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        L7MenuSurface.navigation(panel)
        L7MenuSurface.handle(handle)
        handle.setOnClickListener { expand() }
        handle.setOnTouchListener { _, event -> drag(event) }
        refreshAppearance()
        showExpanded(true)
    }

    /** 连接后收起浮层；断开时按当前值恢复。重复的相同状态不改位置。 */
    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        navigation.setConnected(value)
        showExpanded(!value)
    }

    /**
     * 展开菜单。
     * 投屏里关闭了应用内菜单时不显示浮层，改为回到首页路由，不停止会话。
     */
    fun expand() {
        if (projection && !L7FloatingNavigationPreferences.inAppEnabled(context)) returnHome() else showExpanded(true)
    }

    /** 收起菜单，并在收起前释放当前触控。 */
    fun collapse() = showExpanded(false)

    /** 重新读取悬浮开关、透明度和保存的位置，不重建按钮。 */
    fun refreshAppearance() {
        val enabled = L7FloatingNavigationPreferences.inAppEnabled(context)
        if (floatingEnabled != enabled) {
            floatingEnabled = enabled
            showExpanded(if (enabled) !connected else expanded)
        }
        handle.alpha = 1f - L7FloatingNavigationPreferences.transparency(context) / 100f
        position = L7FloatingNavigationPreferences.position(context)
        positionHandle()
        L7Ui.refresh(this)
    }

    /** 设置页把面板展开成左栏，离开设置页时收起；选中态只在画面和设置之间切换。 */
    fun showPage(page: String) {
        val wasSettings = settingsPage
        settingsPage = L7Routes.navigation(page) == "settings"
        if (wasSettings != settingsPage) updatePanelLayout()
        navigation.select(if (settingsPage) "settings" else "home")
        showExpanded(if (wasSettings && !settingsPage) false else expanded)
    }

    /** 五个按钮复用原实例；设置页只把底板延伸为整高左栏。 */
    private fun updatePanelLayout() {
        val inset = px(R.dimen.galaxy_settings_menu_inset)
        panel.layoutParams =
            LayoutParams(
                if (settingsPage) settingsColumnWidth(context) else px(R.dimen.galaxy_settings_menu_width),
                if (settingsPage) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                val margin = if (settingsPage) 0 else inset
                setMargins(margin, margin, margin, margin)
            }
        val padding = if (settingsPage) inset else 0
        panel.setPadding(padding, padding, padding, padding)
        if (settingsPage) L7SettingsStyle.navigation(panel) else L7MenuSurface.navigation(panel)
    }

    /** 展开前释放触控。关闭悬浮后首页保留固定菜单，投屏完全透传。 */
    private fun showExpanded(value: Boolean) {
        releaseTouches()
        floatingEnabled = L7FloatingNavigationPreferences.inAppEnabled(context)
        val hidden = projection && !floatingEnabled
        val fixedMenu = !projection && !floatingEnabled
        expanded = !hidden && (value || settingsPage || fixedMenu)
        panel.visibility = if (expanded) VISIBLE else GONE
        scrim.visibility = if (expanded && !settingsPage && !fixedMenu) VISIBLE else GONE
        handle.visibility = if (!expanded && !hidden) VISIBLE else GONE
        positionHandle()
    }

    /** 尺寸变化后按保存的比例重新摆放收起把手。 */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        positionHandle()
    }

    /** 把手可移动范围，保留边缘空隙。 */
    private fun travelX() =
        (
            width - px(R.dimen.galaxy_settings_handle_size) -
                px(R.dimen.galaxy_settings_handle_travel_inset)
        ).coerceAtLeast(0).toFloat()

    /** 把手可移动范围，保留边缘空隙。 */
    private fun travelY() =
        (
            height - px(R.dimen.galaxy_settings_handle_size) -
                px(R.dimen.galaxy_settings_handle_travel_inset)
        ).coerceAtLeast(0).toFloat()

    /** 按保存比例放置把手；窗口还没有尺寸时停在边缘。 */
    private fun positionHandle() {
        handle.x = px(R.dimen.galaxy_settings_handle_edge) + travelX() * position.first
        handle.y = px(R.dimen.galaxy_settings_handle_edge) + travelY() * position.second
    }

    /**
     * 拖动超过触摸阈值才记为移动并保存位置。
     * 没有越过阈值的抬起仍是点击，交给展开。
     */
    private fun drag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                originX = handle.x
                originY = handle.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                dragging = dragging || abs(dx) > slop || abs(dy) > slop
                if (dragging) {
                    val edge = px(R.dimen.galaxy_settings_handle_edge).toFloat()
                    handle.x = (originX + dx).coerceIn(edge, edge + travelX())
                    handle.y = (originY + dy).coerceIn(edge, edge + travelY())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val edge = px(R.dimen.galaxy_settings_handle_edge).toFloat()
                    position = (if (travelX() == 0f) 0f else (handle.x - edge) / travelX()) to
                        (if (travelY() == 0f) 0f else (handle.y - edge) / travelY())
                    L7FloatingNavigationPreferences.savePosition(context, position.first, position.second)
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    handle.performClick()
                }
                dragging = false
            }
        }
        return true
    }

    /** 读取主流程尺寸资源。 */
    private fun px(id: Int) = resources.getDimensionPixelSize(id)

    companion object {
        /** 设置左栏占位：菜单宽加两侧页边，与布局资源一致。 */
        fun settingsColumnWidth(context: Context): Int {
            val resources = context.resources
            return resources.getDimensionPixelSize(R.dimen.galaxy_settings_menu_width) +
                resources.getDimensionPixelSize(R.dimen.galaxy_settings_menu_inset) * 2
        }
    }
}
