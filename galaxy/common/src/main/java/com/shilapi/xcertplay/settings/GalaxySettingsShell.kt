package com.shilapi.xcertplay.settings

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.L7FloatingNavigationPreferences
import com.shilapi.xcertplay.L7Header
import com.shilapi.xcertplay.L7ProjectionNavigation
import com.shilapi.xcertplay.L7SettingsStyle
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.host.databinding.ActivityGalaxySettingsBinding

/**
 * 设置宿主的外壳：固定标题、独立滚动正文和导航浮层只创建一次。
 *
 * 普通换页和同密度主题刷新复用导航实例。密度变化由调用方先记下展开状态并丢弃外壳，
 * 下一次显示按保存的展开状态重建。每页滚动位置由调用方的状态表恢复；车辆配置页继续使用原配置视图的滚动容器。
 */
internal class GalaxySettingsShell(
    private val activity: ComponentActivity,
    private val navigate: (String) -> Unit,
) {
    private var binding: ActivityGalaxySettingsBinding? = null
    private var navigationView: L7ProjectionNavigation? = null
    private var navigationDensity = 0
    private var shownPage: String? = null

    /** 当前导航浮层；密度重建完成前为空。 */
    val navigation: L7ProjectionNavigation?
        get() = navigationView

    /** 建立当前导航时的密度，用于判断主题刷新是否要重建。 */
    val densityDpi: Int
        get() = navigationDensity

    /** 当前正文滚动容器；车辆配置页返回其自有滚动视图。 */
    var contentScroll: ScrollView? = null
        private set

    /**
     * 丢掉导航和正文，让下一次 [show] 按新密度重建。
     * 调用方需要先把展开状态记到自己的保存字段。
     */
    fun discardForDensity() {
        binding = null
        navigationView = null
        contentScroll = null
        shownPage = null
    }

    /** 展开现有导航。导航尚未建立时返回 false，由调用方走系统返回。 */
    fun expandNavigation(): Boolean {
        val navigation = navigationView ?: return false
        navigation.expand()
        return true
    }

    /**
     * 把一页放进外壳。
     * [installVehicle] 非空时隐藏普通滚动区，把原配置视图装进车辆槽，并采用它自己的滚动容器。
     * 标题只在设置页出现，并且留在滚动区外面。
     */
    fun show(
        page: String,
        settingsPage: Boolean,
        title: String?,
        backLabel: String,
        onBack: (() -> Unit)?,
        menuExpanded: Boolean,
        scrollPositions: Map<String, Int>,
        installVehicle: ((FrameLayout) -> ScrollView)?,
        onScroll: (ScrollView?) -> Unit,
        buildContent: (LinearLayout) -> Unit,
    ) {
        ensureChrome(menuExpanded)
        val views = binding ?: return
        val inset =
            px(
                if (activity.resources.configuration.screenWidthDp < 600) {
                    R.dimen.galaxy_settings_inset_compact
                } else {
                    R.dimen.galaxy_settings_inset_regular
                },
            )
        views.settingsRoot.setBackgroundColor(activity.getColor(R.color.product_ui_background))
        val params = views.settingsBody.layoutParams as FrameLayout.LayoutParams
        val reserveRail = settingsPage || !L7FloatingNavigationPreferences.inAppEnabled(activity)
        params.marginStart = if (reserveRail) L7ProjectionNavigation.settingsColumnWidth(activity) else 0
        views.settingsBody.layoutParams = params
        if (settingsPage) {
            L7SettingsStyle.content(views.settingsBody)
        } else {
            views.settingsBody.background = null
        }
        views.settingsTitle.removeAllViews()
        if (settingsPage && title != null) {
            views.settingsTitle.visibility = View.VISIBLE
            views.settingsTitle.setPaddingRelative(
                inset,
                px(R.dimen.galaxy_settings_title_padding_top),
                inset,
                px(R.dimen.galaxy_settings_title_padding_bottom),
            )
            views.settingsTitle.addView(
                L7Header(activity, title, backLabel, onBack),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
        } else {
            views.settingsTitle.visibility = View.GONE
        }
        if (installVehicle != null) {
            views.settingsContent.removeAllViews()
            views.settingsScroll.visibility = View.GONE
            views.settingsVehicle.visibility = View.VISIBLE
            views.settingsVehicle.removeAllViews()
            contentScroll = installVehicle(views.settingsVehicle)
        } else {
            views.settingsVehicle.removeAllViews()
            views.settingsVehicle.visibility = View.GONE
            views.settingsScroll.visibility = View.VISIBLE
            val content = views.settingsContent
            content.removeAllViews()
            content.gravity = android.view.Gravity.TOP
            val top =
                if (settingsPage) {
                    R.dimen.galaxy_settings_content_padding_top_settings
                } else {
                    R.dimen.galaxy_settings_content_padding_top
                }
            content.setPaddingRelative(inset, px(top), inset, px(R.dimen.galaxy_settings_content_padding_bottom))
            buildContent(content)
            contentScroll = views.settingsScroll
            val expected = page
            val target = scrollPositions[expected] ?: 0
            val scrollView = views.settingsScroll
            scrollView.post { if (shownPage == expected) scrollView.scrollTo(0, target) }
            scrollView.addOnLayoutChangeListener(
                object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        view: View,
                        left: Int,
                        top: Int,
                        right: Int,
                        bottom: Int,
                        oldLeft: Int,
                        oldTop: Int,
                        oldRight: Int,
                        oldBottom: Int,
                    ) {
                        if (shownPage != expected) {
                            scrollView.removeOnLayoutChangeListener(this)
                            return
                        }
                        val child = scrollView.getChildAt(0) ?: return
                        val range = child.height - scrollView.height
                        if (range > 0) {
                            scrollView.scrollTo(0, target.coerceAtMost(range))
                            scrollView.removeOnLayoutChangeListener(this)
                        }
                    }
                },
            )
        }
        shownPage = page
        onScroll(contentScroll)
        navigationView?.showPage(page)
        navigationView?.refreshAppearance()
    }

    /** 首次或密度重建时安装外壳和导航；同密度刷新只复用已有实例。 */
    private fun ensureChrome(menuExpanded: Boolean) {
        val density = activity.resources.configuration.densityDpi
        if (binding == null) {
            binding = ActivityGalaxySettingsBinding.inflate(activity.layoutInflater)
            activity.setContentView(binding!!.root)
            navigationView = null
        }
        if (navigationView == null) {
            val created = L7ProjectionNavigation(activity, {}, navigate = navigate)
            if (menuExpanded) created.expand() else created.collapse()
            navigationView = created
            navigationDensity = density
            val slot = binding!!.settingsNavigation
            slot.removeAllViews()
            slot.addView(
                created,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
    }

    /** 读取已经放入资源的像素尺寸。 */
    private fun px(id: Int) = activity.resources.getDimensionPixelSize(id)
}
