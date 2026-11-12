package com.galaxy.autocluster.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import com.galaxy.auto.ui.GalaxyStyle
import com.galaxy.autocluster.R
import com.galaxy.autocluster.databinding.ItemThemeLibraryRowBinding
import com.galaxy.autocluster.theme.ThemeLibraryEntry
import com.galaxy.autocluster.theme.ThemeMetadata

/**
 * 主题库两列列表的数据绑定与行复用：每行固定两张卡片，动态部分只从 XML inflate。
 *
 * 本类不读文件、不生成预览、不新建缓存或线程；缩略图统一交给生产入口
 * [ThemeActions.thumbnail]，其 `ImageView.tag` 票据由该入口独占，行复用对象只存在
 * `row.root.tag`。组件库的三列预览是另一个页面，不在此复用。
 * 线程/所有者：主线程；[update] 由主题库页面在数据到达时调用，点击回调随 View 一起释放。
 */
internal class ThemeLibraryAdapter(
    private val context: Context,
    private val thumbnail: (ImageView, ThemeMetadata, Boolean) -> Unit,
    private val edit: (String) -> Unit,
    private val apply: (ThemeMetadata) -> Unit,
    private val more: (ThemeLibraryEntry) -> Unit,
) : BaseAdapter() {
    private var entries = emptyList<ThemeLibraryEntry>()
    private var active: ThemeMetadata? = null

    /** 提交一次真实库快照并刷新列表；不可读数量提示由页面发出，不在 getView 重复弹出。 */
    fun update(
        entries: List<ThemeLibraryEntry>,
        active: ThemeMetadata?,
    ) {
        this.entries = entries
        this.active = active
        notifyDataSetChanged()
    }

    /** 两列固定：奇数尾项仍占一行，由 getView 将第二张卡片置 INVISIBLE。 */
    override fun getCount() = (entries.size + 1) / 2

    /** 返回该行的 1~2 个条目，保持旧 ThemesAdapter 的取值方式。 */
    override fun getItem(position: Int) = entries.drop(position * 2).take(2)

    /** 行 id 与位置一致。 */
    override fun getItemId(position: Int) = position.toLong()

    /**
     * 回填一行两张卡片。复用时文字、状态、按钮、可见性和缩略图目标全部对应本次条目，
     * 不残留上一行数据；尾项只隐藏 View，不创建另一套布局或新导航。
     */
    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
    ): View {
        val row = convertView?.tag as? ItemThemeLibraryRowBinding ?: newRow(parent)
        for ((index, card) in listOf(row.themeCardLeft, row.themeCardRight).withIndex()) {
            val entry = entries.getOrNull(position * 2 + index)
            if (entry == null) {
                card.root.visibility = View.INVISIBLE
                continue
            }
            card.root.visibility = View.VISIBLE
            card.themeCardName.text = entry.metadata.name
            val selected = entry.metadata.id == active?.id
            card.themeCardDetail.text = context.getString(statusText(entry, selected))
            thumbnail(card.themeCardPreview, entry.metadata, false)
            card.themeCardEdit.setOnClickListener { edit(entry.metadata.id) }
            card.themeCardUse.setOnClickListener { apply(entry.metadata) }
            card.themeCardMore.setOnClickListener { more(entry) }
            card.themeCardUse.text =
                context.getString(
                    if (selected && entry.metadata.revision == active?.revision) {
                        R.string.theme_card_applied
                    } else {
                        R.string.theme_card_apply
                    },
                )
        }
        return row.root
    }

    /** 状态文案顺序严格沿旧 when：同 id 异代 → 未应用修改；同代 → 正在使用；内置 → 副本；否则自定义。 */
    private fun statusText(
        entry: ThemeLibraryEntry,
        selected: Boolean,
    ): Int =
        when {
            selected && entry.metadata.revision != active?.revision -> R.string.theme_state_in_use_modified
            selected -> R.string.theme_state_in_use
            entry.builtIn -> R.string.theme_state_builtin
            else -> R.string.theme_state_custom
        }

    /**
     * 只 inflate 已存在 XML 的行骨架并绑定一次公共按钮样式。
     *
     * 更多按钮使用继承公共按钮的本地 Menu style（水平 padding=0），不能调用
     * [GalaxyStyle.button]，否则 48dp 槽内会被重设的普通水平 padding 挤掉 20sp 符号。
     */
    private fun newRow(parent: ViewGroup): ItemThemeLibraryRowBinding {
        val row = ItemThemeLibraryRowBinding.inflate(LayoutInflater.from(context), parent, false)
        for (card in listOf(row.themeCardLeft, row.themeCardRight)) {
            GalaxyStyle.button(card.themeCardEdit, withIcon = false)
            GalaxyStyle.button(card.themeCardUse, withIcon = false)
        }
        row.root.tag = row
        return row
    }
}
