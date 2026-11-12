package com.galaxy.autocluster.ui.pages

import android.view.LayoutInflater
import android.view.View
import com.galaxy.autocluster.databinding.IncludeClusterPageHeaderBinding
import com.galaxy.autocluster.ui.ClusterPageHost
import com.galaxy.autocluster.databinding.ViewClusterActionArrowBinding
import com.galaxy.auto.ui.GalaxySettingRow
import com.galaxy.auto.ui.GalaxyStyle
import com.galaxy.autocluster.ui.navigation.ClusterPage

/**
 * 多页重复的 header/back 与设置行箭头绑定：只抽本批实际重复的两类绑定，
 * 不发展成通用 Builder。返回行用真实返回箭头矢量加文字按钮样式替代旧 `‹ 返回` 字符，
 * 行尾进入箭头用真实 `galaxy_ui_next` 矢量替代旧 `›` 字符。
 *
 * 线程/所有者：主线程；绑定的点击 lambda 由 View 持有，随页面一起释放。
 */
internal object ClusterPageBindings {
    /** 绑定页面头部：标题取页面枚举的标题资源；[back] 非空时显示并绑定标题返回。 */
    fun bindHeader(
        header: IncludeClusterPageHeaderBinding,
        page: ClusterPage,
        host: ClusterPageHost,
    ) {
        header.clusterHeaderTitle.setText(page.titleRes)
        val back = page.titleBack
        if (back == null) {
            header.clusterHeaderBack.visibility = View.GONE
        } else {
            header.clusterHeaderBack.visibility = View.VISIBLE
            header.clusterHeaderBack.setOnClickListener { host.nav(back) }
        }
    }

    /** 绑定可进入的设置行：真实箭头插入操作槽，整行按压反馈与点击事件沿旧 `action` 实现。 */
    fun bindAction(
        row: GalaxySettingRow,
        click: () -> Unit,
    ) {
        val arrow = ViewClusterActionArrowBinding.inflate(LayoutInflater.from(row.context))
        row.setAccessory(arrow.root, leading = false)
        row.isFocusable = true
        GalaxyStyle.rowFeedback(row)
        row.setOnClickListener { click() }
    }
}
