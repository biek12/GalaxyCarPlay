package com.galaxy.autocluster.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.galaxy.autocluster.theme.*

internal class ThemeLibraryPage(
    context: Context,private val actions: ThemeActions,
    private val edit: (String)->Unit,private val create: ()->Unit,private val import: ()->Unit,
    private val apply: (ThemeMetadata)->Unit,private val more: (ThemeLibraryEntry)->Unit,
): ListView(context) {
    private val ui=ClusterUi(context)
    private var entries=emptyList<ThemeLibraryEntry>()
    private var active: ThemeMetadata?=null
    private val currentName=ui.label("正在读取主题库",18f,true)
    private val currentPreview=RatioImage(context)
    private val listAdapter=ThemesAdapter()
    init {
        divider=null;dividerHeight=ui.dp(14);clipToPadding=false
        val header=ui.column()
        header.addView(ui.label("当前主题",23f));header.addView(currentName,LinearLayout.LayoutParams(-1,-2).apply { topMargin=ui.dp(10);bottomMargin=ui.dp(18) })
        header.addView(currentPreview,LinearLayout.LayoutParams(-1,-2))
        header.addView(ui.note("主题样式预览 · 实时地图与数据可在编辑器中查看"))
        val row=ui.row()
        row.addView(ui.button("编辑当前主题",true){active?.let { edit(it.id) }},LinearLayout.LayoutParams(0,-2,1f))
        row.addView(ui.button("新建主题"){create()},LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=ui.dp(12) })
        row.addView(ui.button("导入主题包"){import()},LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=ui.dp(12) })
        header.addView(row)
        header.addView(ui.label("主题库",23f),LinearLayout.LayoutParams(-1,-2).apply { topMargin=ui.dp(28);bottomMargin=ui.dp(18) })
        addHeaderView(header,null,false);adapter=listAdapter
    }
    fun update(library: ThemeLibrary,active: ThemeMetadata?){
        entries=library.entries;this.active=active
        currentName.text=active?.name?:"尚未应用主题"
        active?.let { actions.thumbnail(currentPreview,it,true) }
        listAdapter.notifyDataSetChanged()
        if(library.unreadableIds.isNotEmpty())Toast.makeText(context,"${library.unreadableIds.size} 个主题无法读取，文件已保留",Toast.LENGTH_LONG).show()
    }
    private data class Cell(val root: LinearLayout,val preview: ImageView,val name: TextView,val detail: TextView,val edit: Button,val use: Button,val menu: Button)
    private data class Row(val root: LinearLayout,val cells: List<Cell>)
    private inner class ThemesAdapter: BaseAdapter(){
        override fun getCount()=(entries.size+1)/2
        override fun getItem(position: Int)=entries.drop(position*2).take(2)
        override fun getItemId(position: Int)=position.toLong()
        override fun getView(position: Int,convertView: View?,parent: ViewGroup): View {
            val row=convertView?.tag as? Row?:newRow()
            for((index,cell)in row.cells.withIndex()){
                val entry=entries.getOrNull(position*2+index)
                if(entry==null){cell.root.visibility=View.INVISIBLE;continue}
                cell.root.visibility=View.VISIBLE;cell.name.text=entry.metadata.name
                val selected=entry.metadata.id==active?.id
                cell.detail.text=when{selected&&entry.metadata.revision!=active?.revision->"使用中 · 有未应用修改";selected->"正在使用";entry.builtIn->"内置模板 · 保存为副本";else->"自定义主题"}
                actions.thumbnail(cell.preview,entry.metadata)
                cell.edit.setOnClickListener { edit(entry.metadata.id) };cell.use.setOnClickListener { apply(entry.metadata) };cell.menu.setOnClickListener { more(entry) }
                cell.use.text=if(selected&&entry.metadata.revision==active?.revision)"已应用"else"应用"
            }
            return row.root
        }
    }
    private fun newRow(): Row {
        val root=ui.row()
        val cells=(0..1).map { index->
            val card=ui.column(12).apply { background=ui.rounded(ui.surface) }
            val image=RatioImage(context);card.addView(image,LinearLayout.LayoutParams(-1,-2))
            val title=ui.label("",18f).apply { minLines=1;maxLines=2 };card.addView(title,LinearLayout.LayoutParams(-1,-2).apply { topMargin=ui.dp(14) })
            val detail=ui.label("",13f,true);card.addView(detail,LinearLayout.LayoutParams(-1,-2).apply { topMargin=ui.dp(8);bottomMargin=ui.dp(14) })
            val buttons=ui.row();val editButton=ui.button("编辑"){};val useButton=ui.button("应用"){};val menu=ui.button("⋯"){}
            buttons.addView(editButton,LinearLayout.LayoutParams(0,-2,1f))
            buttons.addView(useButton,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=ui.dp(8) })
            buttons.addView(menu,LinearLayout.LayoutParams(ui.dp(48),-2).apply { marginStart=ui.dp(8) })
            card.addView(buttons);root.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply { if(index==0)marginEnd=ui.dp(14) })
            Cell(card,image,title,detail,editButton,useButton,menu)
        }
        return Row(root,cells).also { root.tag=it }
    }
    private class RatioImage(context: Context): ImageView(context){
        init { scaleType=ScaleType.FIT_CENTER;setBackgroundColor(android.graphics.Color.rgb(9,20,23)) }
        override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int){val width=MeasureSpec.getSize(widthMeasureSpec);super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec((width*532f/1920).toInt().coerceAtLeast(1),MeasureSpec.EXACTLY))}
    }
}
