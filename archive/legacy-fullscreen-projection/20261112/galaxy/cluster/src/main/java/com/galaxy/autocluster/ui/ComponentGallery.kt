package com.galaxy.autocluster.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** 三列样式预览。浏览组件库不修改编辑文档，只有编辑器传入choose时才允许添加。 */
internal class ComponentGallery(context: Context,private val choose: ((ComponentPreset)->Unit)?=null): ListView(context) {
    private val ui=ClusterUi(context)
    private val styles=ComponentPresets.all
    private val galleryAdapter=GalleryAdapter()
    init {
        divider=null;dividerHeight=ui.dp(12);clipToPadding=false
        setPadding(0,ui.dp(8),0,ui.dp(18));adapter=galleryAdapter
        contentDescription=if(choose==null)"组件样式预览，每行三个"else"选择要添加的组件样式"
    }
    private data class Cell(val root: LinearLayout,val sample: ComponentSampleView,val title: TextView)
    private data class Row(val root: LinearLayout,val cells: List<Cell>)
    private inner class GalleryAdapter: BaseAdapter(){
        override fun getCount()=(styles.size+2)/3
        override fun getItem(position: Int)=styles.drop(position*3).take(3)
        override fun getItemId(position: Int)=position.toLong()
        override fun getView(position: Int,convertView: View?,parent: ViewGroup): View {
            val row=convertView?.tag as? Row?:createRow()
            for((index,cell)in row.cells.withIndex()){
                val style=styles.getOrNull(position*3+index)
                if(style==null){cell.root.visibility=View.INVISIBLE;continue}
                cell.root.visibility=View.VISIBLE;cell.title.text=style.title;cell.sample.bind(style)
                cell.root.setOnClickListener { if(choose!=null)choose.invoke(style)else showDetails(style) }
                cell.root.contentDescription="${style.title}，样式预览"
            }
            return row.root
        }
    }
    private fun createRow(): Row {
        val root=ui.row()
        val cells=(0..2).map { index->
            val card=ui.column(10).apply { background=ui.rounded(ui.surface);isFocusable=true }
            val preview=ComponentSampleView(context)
            card.addView(preview,LinearLayout.LayoutParams(-1,ui.dp(140)))
            val title=ui.label("",15f).apply { setPadding(0,ui.dp(12),0,0);minLines=2;maxLines=2 }
            card.addView(title)
            root.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply { if(index<2)marginEnd=ui.dp(12) })
            Cell(card,preview,title)
        }
        return Row(root,cells).also { root.tag=it }
    }
    private fun showDetails(style: ComponentPreset){
        val content=ui.column(20)
        val sample=ComponentSampleView(context).apply { bind(style) }
        content.addView(sample,LinearLayout.LayoutParams(-1,ui.dp(330)))
        content.addView(ui.note("样式与动画使用演示数据，不会添加到仪表或修改当前主题。"))
        val controls=ui.row()
        controls.addView(ui.button("重播动画"){sample.replay()},LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(ui.button("日夜预览"){sample.showNight=!sample.showNight},LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=ui.dp(12) })
        content.addView(controls)
        val dialog=AlertDialog.Builder(context).setTitle(style.title).setView(content).setPositiveButton("关闭",null).create()
        dialog.setOnDismissListener { sample.release() };dialog.show()
    }
}
