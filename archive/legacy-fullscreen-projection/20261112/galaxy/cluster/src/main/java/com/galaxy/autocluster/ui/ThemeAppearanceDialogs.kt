package com.galaxy.autocluster.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.*
import com.galaxy.autocluster.theme.*

/** 背景、主题token与日夜共享独立设置；不更改车辆/手机地图的日夜配置。 */
internal class ThemeAppearanceDialogs(
    private val context: Context,private val changed: ()->Unit,
    private val imageRequested: (EditorSession,Boolean)->Unit,
) {
    private val ui=ClusterUi(context)
    fun open(session: EditorSession){
        AlertDialog.Builder(context).setTitle("主题外观").setItems(arrayOf("当前模式背景","主题配色","日夜与共享")){_,which->
            when(which){0->background(session);1->palette(session);else->appearance(session)}
        }.show()
    }
    private fun background(session: EditorSession){
        val night=session.previewNight&&!session.content.scene.document.shareDayNight
        val current=if(night)session.content.scene.document.nightBackground else session.content.scene.document.dayBackground
        val body=ui.column(20)
        val types=listOf("solid" to "纯色","gradient" to "渐变","image" to "本地图片")
        val kind=spinner(types.map { it.second },types.indexOfFirst { it.first==current.kind })
        body.addView(ui.label("背景类型",17f));body.addView(kind)
        val color=field(body,"主色（#RRGGBB / #AARRGGBB）",current.color)
        val second=field(body,"渐变第二色",current.second)
        val angle=field(body,"渐变角度",current.angle.toString())
        val dialog=AlertDialog.Builder(context).setTitle(if(night)"夜间背景"else"日间背景").setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        body.addView(ui.button("选择背景图片"){
            dialog.dismiss();imageRequested(session,night)
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=ui.dp(18) })
        body.addView(ui.note(current.image.substringAfterLast('/').ifEmpty { "所选图片会复制到主题包，导出后仍可使用。" }))
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val next=current.copy(kind=types[kind.selectedItemPosition].first,color=color.text.toString().trim(),second=second.text.toString().trim(),angle=angle.text.toString().toFloat())
                val c=session.content
                val document=if(night)c.scene.document.copy(nightBackground=next)else c.scene.document.copy(dayBackground=next)
                session.change(c.copy(scene=c.scene.copy(document=document)));changed();dialog.dismiss()
            }catch(error: Exception){Toast.makeText(context,"背景设置无效；图片背景请先选择本地图片",Toast.LENGTH_LONG).show()}
        }
    }
    private fun palette(session: EditorSession){
        val night=session.previewNight&&!session.content.scene.document.shareDayNight
        val current=if(night)session.content.scene.styles.nightPalette else session.content.scene.styles.dayPalette
        val body=ui.column(20)
        val names=mapOf("foreground" to "主要文字","muted" to "次要文字","accent" to "强调色","panel" to "卡片色","track" to "底轨色","warning" to "提醒色","critical" to "严重提醒色")
        val inputs=current.mapValues { (key,value)->field(body,names[key]?:key,value) }
        val dialog=AlertDialog.Builder(context).setTitle(if(night)"夜间主题配色"else"日间主题配色").setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val c=session.content;val values=inputs.mapValues { it.value.text.toString().trim() }
                val styles=if(night)c.scene.styles.copy(nightPalette=values)else c.scene.styles.copy(dayPalette=values)
                session.change(c.copy(scene=c.scene.copy(styles=styles)));changed();dialog.dismiss()
            }catch(error: Exception){Toast.makeText(context,"请输入有效的颜色值",Toast.LENGTH_LONG).show()}
        }
    }
    private fun appearance(session: EditorSession){
        val body=ui.column(20);val document=session.content.scene.document
        val choices=listOf("auto" to "跟随系统","day" to "日间","night" to "夜间")
        body.addView(ui.label("主题日夜模式",18f))
        val mode=spinner(choices.map { it.second },choices.indexOfFirst { it.first==document.appearance });body.addView(mode)
        val share=Switch(context).apply { text="共享日夜样式";isChecked=document.shareDayNight;minimumHeight=ui.dp(64);setTextColor(ui.foreground);textSize=18f }
        body.addView(share);body.addView(ui.note("共享时两种模式使用日间配置；原来的夜间配置仍保留。全局仪表日夜设置可覆盖此选择。"))
        AlertDialog.Builder(context).setTitle("日夜与共享").setView(body).setPositiveButton("保存"){_,_->
            val c=session.content
            session.change(c.copy(scene=c.scene.copy(document=c.scene.document.copy(appearance=choices[mode.selectedItemPosition].first,shareDayNight=share.isChecked))))
            changed()
        }.setNegativeButton("取消",null).show()
    }
    private fun field(parent: LinearLayout,title: String,value: String): EditText {
        parent.addView(ui.label(title,16f),LinearLayout.LayoutParams(-1,-2).apply { topMargin=ui.dp(16);bottomMargin=ui.dp(8) })
        return ui.input(value).also { parent.addView(it,LinearLayout.LayoutParams(-1,-2)) }
    }
    private fun spinner(values: List<String>,selected: Int)=Spinner(context).apply {
        adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,values);setSelection(selected.coerceAtLeast(0))
        minimumHeight=ui.dp(56)
    }
}
