package com.shilapi.xcertplay.settings

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.L7DesktopNavigation
import com.shilapi.xcertplay.L7FloatingNavigationPreferences
import com.shilapi.xcertplay.L7HomePanel
import com.shilapi.xcertplay.L7ProjectionNavigation
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.host.databinding.PageGalaxyHomeBinding

/**
 * 首页只装配静态页面和权限提示。
 *
 * 宽度沿用小屏 16dp、常规 24dp 的页边，以及悬浮关闭时的 164dp 菜单留白，上限 560dp。
 * 权限提示只有桌面悬浮配置打开且系统尚未授权时才可见、可点。
 */
internal class GalaxyHomePage(
    private val activity: ComponentActivity,
    private val onQuickConnect: () -> Unit,
    private val onWireless: () -> Unit,
    private val onUsb: () -> Unit,
    private val onSettings: () -> Unit,
) {
    /** 首页连接面板，供状态刷新调用原有 [L7HomePanel.update]。 */
    var panel: L7HomePanel? = null
        private set

    /** 桌面悬浮权限提示；不可点时不保留点击监听。 */
    var permissionHint: android.widget.TextView? = null
        private set

    /** 把首页放进正文槽，并按当前窗口计算面板宽度。 */
    fun bind(parent: LinearLayout) {
        val binding = PageGalaxyHomeBinding.inflate(activity.layoutInflater, parent, false)
        val home = L7HomePanel(activity, onQuickConnect, onWireless, onUsb, onSettings)
        binding.homePanelSlot.addView(
            home,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        panel = home
        permissionHint = binding.desktopPermissionHint
        showPermissionHint(false)
        parent.gravity = Gravity.CENTER
        parent.addView(binding.root, LinearLayout.LayoutParams(panelWidth(), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /**
     * 同步权限提示的可见和点击。
     * 配置未启用或已经授权时移除监听，避免隐藏视图仍响应点击。
     */
    fun showPermissionHint(visible: Boolean) {
        val hint = permissionHint ?: return
        hint.visibility = if (visible) View.VISIBLE else View.GONE
        hint.setOnClickListener(
            if (visible) {
                View.OnClickListener { L7DesktopNavigation.permission(activity) }
            } else {
                null
            },
        )
        hint.isClickable = visible
    }

    /** 首页内容宽度：扣除页边和固定菜单后不超过资源里的 560dp。 */
    private fun panelWidth(): Int {
        val metrics = activity.resources.displayMetrics
        val screen = activity.resources.configuration.screenWidthDp
        val insetId = if (screen < 600) R.dimen.galaxy_settings_inset_compact else R.dimen.galaxy_settings_inset_regular
        val inset = (activity.resources.getDimensionPixelSize(insetId) / metrics.density).toInt()
        val menu =
            if (L7FloatingNavigationPreferences.inAppEnabled(activity)) {
                0
            } else {
                L7ProjectionNavigation.settingsColumnWidth(activity)
            }
        val available = ((screen - inset * 2) * metrics.density).toInt() - menu
        val max = activity.resources.getDimensionPixelSize(R.dimen.galaxy_settings_home_max_width)
        return available.coerceIn(1, max)
    }
}
