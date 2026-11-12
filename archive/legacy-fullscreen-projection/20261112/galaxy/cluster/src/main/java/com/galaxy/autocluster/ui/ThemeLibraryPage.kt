package com.galaxy.autocluster.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.ListView
import android.widget.Toast
import com.galaxy.auto.ui.GalaxyStyle
import com.galaxy.autocluster.R
import com.galaxy.autocluster.databinding.HeaderThemeLibraryBinding
import com.galaxy.autocluster.theme.ThemeLibrary
import com.galaxy.autocluster.theme.ThemeLibraryEntry
import com.galaxy.autocluster.theme.ThemeMetadata

/**
 * 主题库页面：ListView 宿主 + XML 当前主题头部 + 两列卡片 Adapter。
 *
 * 只负责头部呈现、库数据 update 与不可读数量提示；缩略图由生产入口
 * [ThemeActions.thumbnail] 经 [thumbnail] 注入，本页与 Adapter 都不复制其票据/缓存/线程。
 * 线程/所有者：主线程；生命周期随 Activity 外壳，不持有后台连接。
 */
internal class ThemeLibraryPage(
    context: Context,
    private val thumbnail: (ImageView, ThemeMetadata, Boolean) -> Unit,
    private val edit: (String) -> Unit,
    private val create: () -> Unit,
    private val import: () -> Unit,
    private val apply: (ThemeMetadata) -> Unit,
    private val more: (ThemeLibraryEntry) -> Unit,
) : ListView(context) {
    private val header = HeaderThemeLibraryBinding.inflate(LayoutInflater.from(context))
    private val listAdapter = ThemeLibraryAdapter(context, thumbnail, edit, apply, more)
    private var active: ThemeMetadata? = null

    init {
        divider = null
        dividerHeight = resources.getDimensionPixelSize(R.dimen.theme_list_divider_height)
        clipToPadding = false
        // 头部按钮复用公共主/次样式；编辑当前主题为唯一主操作，其余保持次操作。
        GalaxyStyle.button(header.themeEditCurrent, primary = true, withIcon = false)
        GalaxyStyle.button(header.themeCreate, withIcon = false)
        GalaxyStyle.button(header.themeImport, withIcon = false)
        header.themeEditCurrent.setOnClickListener { active?.let { edit(it.id) } }
        header.themeCreate.setOnClickListener { create() }
        header.themeImport.setOnClickListener { import() }
        addHeaderView(header.root, null, false)
        adapter = listAdapter
    }

    /**
     * 呈现一次真实主题库快照。
     *
     * 当前主题为空时显式清空预览与旧票据，避免上一代异步缩略图回填冒充当前主题；
     * 非空时才以 active=true 请求当前预览。不可读数量提示只随数据 update 发出一次。
     */
    fun update(
        library: ThemeLibrary,
        active: ThemeMetadata?,
    ) {
        this.active = active
        header.themeCurrentName.text = active?.name ?: context.getString(R.string.theme_current_empty)
        if (active == null) {
            header.themeCurrentPreview.tag = null
            header.themeCurrentPreview.setImageDrawable(null)
        } else {
            thumbnail(header.themeCurrentPreview, active, true)
        }
        listAdapter.update(library.entries, active)
        val unreadable = library.unreadableIds.size
        if (unreadable > 0) {
            val text =
                resources.getQuantityString(
                    R.plurals.theme_unreadable_count,
                    unreadable,
                    unreadable,
                )
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}
