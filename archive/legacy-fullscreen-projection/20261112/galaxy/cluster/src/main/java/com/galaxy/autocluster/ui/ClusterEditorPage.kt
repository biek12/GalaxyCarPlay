package com.galaxy.autocluster.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import com.galaxy.autocluster.runtime.*
import com.galaxy.autocluster.theme.*

/** 竖屏车机：固定宽屏画布，属性独立滚动；所有编辑只改变草稿，保存/应用由外部事务处理。 */
internal class ClusterEditorPage(
    context: Context,private val output: ClusterSceneOutput,
    private val save: (Boolean)->Unit,private val background: ()->Unit,
    private val fields: ()->List<Pair<String,String>>,private val asset: (String,PropertySpec)->Unit,
): LinearLayout(context) {
    private val ui=ClusterUi(context)
    private val title=ui.label("编辑主题",22f)
    private val dirty=ui.label("",14f,true)
    private val undo=ui.button("撤销"){operate { undo() }}
    private val redo=ui.button("重做"){operate { redo() }}
    private val dayButton=ui.button("日间"){mode(false)}
    private val nightButton=ui.button("夜间"){mode(true)}
    private val canvas=ClusterEditorCanvas(context,output,edited={commit->changed(commit)},selectionChanged={properties.bind(session)})
    private val properties=ComponentPropertyPanel(context,changed={changed(false)},fields,asset)
    private var session: EditorSession?=null

    init {
        orientation=VERTICAL
        val heading=ui.row();val names=ui.column();names.addView(title);names.addView(dirty,LayoutParams(-1,-2).apply { topMargin=ui.dp(8) })
        heading.addView(names,LayoutParams(0,-2,1f))
        heading.addView(ui.button("保存"){if(commit())save(false)},LayoutParams(-2,-2).apply { marginStart=ui.dp(10) })
        heading.addView(ui.button("应用到仪表",true){if(commit())save(true)},LayoutParams(-2,-2).apply { marginStart=ui.dp(10) })
        addView(heading)
        val bar=ui.row();bar.addView(dayButton);bar.addView(nightButton,LayoutParams(-2,-2).apply { marginStart=ui.dp(8) })
        bar.addView(Space(context),LayoutParams(0,1,1f));bar.addView(undo);bar.addView(redo,LayoutParams(-2,-2).apply { marginStart=ui.dp(8) })
        addView(bar,LayoutParams(-1,-2).apply { topMargin=ui.dp(18);bottomMargin=ui.dp(12) })
        addView(canvas,LayoutParams(-1,-2))
        val previewControls=ui.row()
        previewControls.addView(ui.label("1920 × 532",13f,true),LayoutParams(0,-2,1f))
        previewControls.addView(ui.button("实时 / 演示"){canvas.demo=!canvas.demo;refreshNote()})
        previewControls.addView(ui.button("预览下一页"){canvas.nextPage()},LayoutParams(-2,-2).apply { marginStart=ui.dp(8) })
        previewControls.addView(ui.button("重播动画"){canvas.replay();refreshNote()},LayoutParams(-2,-2).apply { marginStart=ui.dp(8) })
        previewControls.addView(ui.button("参考线"){canvas.showGuides=!canvas.showGuides},LayoutParams(-2,-2).apply { marginStart=ui.dp(8) })
        addView(previewControls,LayoutParams(-1,-2).apply { topMargin=ui.dp(8) })
        val tools=ui.row()
        tools.addView(ui.button("＋ 添加组件",true){addComponent()},LayoutParams(0,-2,1.3f))
        tools.addView(ui.button("图层"){layers()},LayoutParams(0,-2,1f).apply { marginStart=ui.dp(10) })
        tools.addView(ui.button("背景"){if(commit())background()},LayoutParams(0,-2,1f).apply { marginStart=ui.dp(10) })
        tools.addView(ui.button("更多"){more()},LayoutParams(0,-2,1f).apply { marginStart=ui.dp(10) })
        addView(tools,LayoutParams(-1,-2).apply { topMargin=ui.dp(14);bottomMargin=ui.dp(14) })
        addView(ScrollView(context).apply { isFillViewport=true;addView(properties) },LayoutParams(-1,0,1f))
    }
    fun bind(value: EditorSession){
        session=value;canvas.session=value
        if(value.selection.isEmpty())value.choose(SceneTree.flatten(value.content.scene.document.nodes).firstOrNull { it.type=="gauge"||it.type=="value" }?.id)
        mode(value.previewNight);properties.bind(value);changed(false)
    }
    fun prepared(value: PreparedScene)=canvas.prepared(value)
    fun snapshot(value: Bundle)=canvas.snapshot(value)
    private fun mode(night: Boolean){
        session?.previewNight=night;output.forceNight(night)
        dayButton.alpha=if(night).55f else 1f;nightButton.alpha=if(night)1f else .55f
        properties.bind(session);canvas.refresh()
    }
    fun refresh(){properties.bind(session);changed(false)}
    private fun changed(inspector: Boolean){
        val state=session?:return
        title.text=state.name
        dirty.text=if(state.dirty)"有未保存的修改"else if(state.expectedRevision==null)"保存后生成自定义主题"else"已保存"
        undo.isEnabled=state.canUndo;redo.isEnabled=state.canRedo
        if(inspector)properties.bind(state)else properties.refreshInheritance(state)
        canvas.refresh();output.submit(SceneInput(state.metadata.id,state.content))
    }
    private fun operate(action: EditorSession.()->Unit){
        val state=session?:return
        try { state.action();properties.bind(state);changed(false) }catch(error: Exception){Toast.makeText(context,"操作未完成，已保留当前布局",Toast.LENGTH_LONG).show()}
    }
    fun commit(): Boolean {
        if(!properties.commitAll()){Toast.makeText(context,"请先修正属性面板中的输入",Toast.LENGTH_LONG).show();return false}
        session?.finishGesture();return true
    }
    private fun addComponent(){
        if(!commit())return
        lateinit var dialog: AlertDialog
        val gallery=ComponentGallery(context){preset->operate { add(preset.type,preset.properties) };dialog.dismiss()}
        dialog=AlertDialog.Builder(context).setTitle("添加组件 · 选择样式").setView(gallery).setNegativeButton("取消",null).create()
        dialog.show()
    }
    private fun layers(){
        val state=session?:return
        val nodes=SceneTree.flatten(state.content.scene.document.nodes).asReversed()
        val labels=nodes.map { (if(it.visible)""else"隐藏 · ")+(if(it.locked)"锁定 · "else"")+it.name }
        AlertDialog.Builder(context).setTitle("图层 · 从上到下").setItems(labels.toTypedArray()){_,which->state.choose(nodes[which].id);properties.bind(state);canvas.refresh()}.setNegativeButton("关闭",null).show()
    }
    private fun more(){
        val state=session?:return
        val options=arrayOf("重命名主题",if(canvas.multiSelect)"关闭多选"else"开启多选","复制所选组件","删除所选组件","置于顶层","置于底层","组合所选组件","解组","水平居中","垂直居中","靠左","靠右","靠上","靠下")
        AlertDialog.Builder(context).setTitle("编辑操作").setItems(options){_,which->
            if(which==0){
                val input=ui.input(state.name)
                AlertDialog.Builder(context).setTitle("主题名称").setView(input).setPositiveButton("确定"){_,_->operate { rename(input.text.toString().trim()) }}.setNegativeButton("取消",null).show();return@setItems
            }
            if(which==1){canvas.multiSelect=!canvas.multiSelect;Toast.makeText(context,if(canvas.multiSelect)"点选多个组件后可组合或移动"else"已关闭多选",Toast.LENGTH_SHORT).show();return@setItems}
            operate {
                when(which){
                    2->duplicateSelection();3->deleteSelection();4->selection.singleOrNull()?.let { reorder(it,true) };5->selection.singleOrNull()?.let { reorder(it,false) }
                    6->group();7->ungroup();8->align("horizontal");9->align("vertical");10->align("left");11->align("right");12->align("top");13->align("bottom")
                }
            }
        }.show()
    }
    private fun refreshNote(){Toast.makeText(context,if(canvas.demo)"演示数据仅用于编辑预览"else"实时地图由CarPlay提供",Toast.LENGTH_SHORT).show()}
    fun close(){canvas.close()}
}
