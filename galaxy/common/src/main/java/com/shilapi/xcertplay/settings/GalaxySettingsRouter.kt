package com.shilapi.xcertplay.settings

import com.shilapi.xcertplay.L7Routes

/**
 * 设置导航只做页面计算和具体动作派发，视图与会话仍由宿主持有。
 *
 * 历史别名、返回层和「设置回到上次分类」都委托 [L7Routes]。
 * 未保存的车辆配置必须先走原草稿确认，确认前不清理页面。
 */
internal class GalaxySettingsRouter(
    private val vehicleDirty: () -> Boolean,
    private val requestLeave: (proceed: () -> Unit) -> Unit,
    private val openConnectionMenu: (openSettings: () -> Unit) -> Unit,
    private val confirmExit: () -> Unit,
    private val openCarHome: () -> Unit,
    private val sessionActive: () -> Boolean,
    private val openProjection: () -> Unit,
    private val expandNavigation: () -> Boolean,
    private val systemBack: () -> Unit,
    private val page: () -> String,
    private val setPage: (String) -> Unit,
    private val lastSettingsPage: () -> String,
    private val renderedPage: () -> String?,
    private val show: () -> Unit,
) {
    /** 把 Intent 或保存状态中的历史别名收成现行页面。 */
    fun normalize(raw: String): String = L7Routes.normalize(raw)

    /** 返回键的上一层页面；首页的菜单展开不在这里处理。 */
    fun backTarget(raw: String): String = L7Routes.back(raw)

    /**
     * 处理菜单目的地。
     * 草稿未保存时只发起原确认，确认回调里才重新选择；连接、退出、车机首页和已连接画面不走普通换页。
     */
    fun select(destination: String) {
        if (vehicleDirty()) {
            requestLeave { select(destination) }
            return
        }
        when {
            destination == "connection" ->
                openConnectionMenu {
                    setPage("settings-connection")
                    show()
                }
            destination == "exit" -> confirmExit()
            destination == "car-home" -> openCarHome()
            destination == "home" && sessionActive() -> openProjection()
            else -> {
                val next = L7Routes.destination(page(), destination, lastSettingsPage())
                if (page() != next) {
                    setPage(next)
                    show()
                }
            }
        }
    }

    /**
     * 处理返回键。
     * 非首页先回到上一层，再由显示入口做草稿保护；首页展开原导航，导航还没建立时才交给系统返回。
     */
    fun back() {
        if (page() != "home") {
            setPage(L7Routes.back(page()))
            show()
            return
        }
        if (!expandNavigation()) systemBack()
    }

    /**
     * 显示已经写入的目标页。
     * 正在离开未保存的车辆配置时先把页面拨回去并确认，确认后才执行清理和绘制。
     */
    fun guardVehicleLeave(showPrepared: () -> Unit) {
        val requested = page()
        if (renderedPage() == "settings-vehicle" && requested != "settings-vehicle" && vehicleDirty()) {
            setPage("settings-vehicle")
            requestLeave {
                setPage(requested)
                guardVehicleLeave(showPrepared)
            }
            return
        }
        showPrepared()
    }
}
