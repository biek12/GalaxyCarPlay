package com.galaxy.autocluster

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.*
import com.galaxy.autocluster.runtime.*
import com.galaxy.autocluster.theme.*
import com.galaxy.autocluster.theme.render.VehiclePainter
import com.galaxy.autocluster.ui.*

/** 车机UI仅编辑/选择主题。数据、地图与HAB owner仍在AutoService，后台主题不依赖此Activity。 */
class ThemeActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.galaxy.autocluster.runtime.ClusterAppearance.wrap(base))
    private lateinit var ui: ClusterUi
    private lateinit var actions: ThemeActions
    private lateinit var content: FrameLayout
    private lateinit var footer: TextView
    private lateinit var library: ThemeLibraryPage
    private val tabs=mutableListOf<Button>()
    private var observer: AutoClient?=null
    private var preview: ClusterSceneOutput?=null
    private var editor: ClusterEditorPage?=null
    private var gallery: ComponentGallery?=null
    private var session: EditorSession?=null
    private var snapshot=Bundle()
    private var page=0
    private var requestGeneration=0
    private var pendingExport: ThemeMetadata?=null
    private var pickingDisplayImage=false
    private var displayDraft: ClusterDisplayConfiguration?=null
    private sealed class AssetTarget(val session: EditorSession,val night: Boolean){
        class Node(session: EditorSession,night: Boolean,val id: String,val property: PropertySpec):AssetTarget(session,night)
        class Background(session: EditorSession,night: Boolean):AssetTarget(session,night)
    }
    private var pendingAsset: AssetTarget?=null
    private val appearance by lazy { ThemeAppearanceDialogs(this,{editor?.refresh()}){session,night->
        pendingAsset=AssetTarget.Background(session,night);pickAsset(false)
    } }

    override fun onCreate(state: Bundle?){
        super.onCreate(state)
        ui=ClusterUi(this);actions=ThemeActions(this,::message)
        buildPage()
        observer=AutoClient(this,AutoContract.OBSERVER,onSnapshot={value->
            snapshot=value;editor?.snapshot(value)
            footer.text=when{value.getBoolean("qnxReady")->"仪表通道已连接";value.getBoolean("rootReady")->"基础服务已就绪";else->"AutoCluster · ${value.getString("status").orEmpty()}"}
        },onState={footer.text="AutoCluster · $it"}).also {it.connect()}
        loadLibrary()
    }
    private fun buildPage(){
        val root=ui.column(20).apply { setBackgroundColor(ui.background) }
        val header=ui.row();header.addView(ui.label("仪表 · AutoCluster",26f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(ui.button("返回仪表"){finish()});root.addView(header)
        val bar=ui.row()
        for((index,title)in listOf("我的主题","编辑主题","组件库").withIndex()){
            val button=ui.button(title,index==0){switchPage(index)}
            bar.addView(button,LinearLayout.LayoutParams(0,ui.dp(56),1f));tabs.add(button)
        }
        root.addView(bar,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(22);bottomMargin=ui.dp(22)})
        content=FrameLayout(this);root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        footer=ui.label("正在连接 AutoService",13f,true)
        root.addView(footer,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(14)})
        setContentView(root)
        library=ThemeLibraryPage(this,actions,::openEditor,::newTheme,::pickTheme,::applyTheme,::themeMenu)
        content.addView(library,FrameLayout.LayoutParams(-1,-1))
    }
    private fun loadLibrary(){actions.library { library,active->this.library.update(library,active) }}
    private fun switchPage(index: Int){
        if(index==1&&session==null){actions.edit("builtin-gt"){setEditor(it)};return}
        page=index;content.removeAllViews()
        tabs.forEachIndexed {i,button->button.alpha=if(i==index)1f else .6f}
        when(index){
            0->{content.addView(library,FrameLayout.LayoutParams(-1,-1));loadLibrary()}
            1->{ensureEditor();content.addView(editor,FrameLayout.LayoutParams(-1,-1));session?.let {editor?.bind(it)}}
            else->{if(gallery==null)gallery=ComponentGallery(this);content.addView(gallery,FrameLayout.LayoutParams(-1,-1))}
        }
    }
    private fun ensureEditor(){
        if(editor!=null)return
        val output=ClusterSceneOutput(this,AutoContract.PREVIEW,onStatus={footer.text=it},onPrepared={prepared->
            val current=editor
            if(current==null)prepared.close()else current.prepared(prepared)
        })
        preview=output
        editor=ClusterEditorPage(this,output,save={apply->saveTheme(apply)},background={session?.let(appearance::open)},fields=::fields,asset={id,property->
            val current=session?:return@ClusterEditorPage
            pendingAsset=AssetTarget.Node(current,current.previewNight&&!current.content.scene.document.shareDayNight,id,property)
            pickAsset(property.key=="fontAsset")
        })
    }
    private fun setEditor(value: EditorSession){session=value;ensureEditor();switchPage(1)}
    private fun leaveDraft(proceed: ()->Unit){
        val current=session
        if(current?.dirty!=true){proceed();return}
        com.galaxy.auto.ui.GalaxyDialogs.builder(this).setTitle("保留当前修改？").setMessage("当前主题有未保存的修改。")
            .setPositiveButton("保存并继续"){_,_->if(editor?.commit()!=false)actions.save(current,false){proceed()}}
            .setNeutralButton("放弃修改"){_,_->proceed()}.setNegativeButton("继续编辑",null).show()
    }
    private fun openEditor(id: String){leaveDraft {
        val ticket=++requestGeneration
        actions.edit(id){if(ticket==requestGeneration)setEditor(it)}
    }}
    private fun newTheme(){leaveDraft {val ticket=++requestGeneration;actions.blank {if(ticket==requestGeneration)setEditor(it)}}}
    private fun saveTheme(apply: Boolean){
        val current=session?:return
        actions.save(current,apply){result->
            if(session===current)editor?.refresh()
            if(result.applied)startOutput()else if(!apply)message("主题已保存，正在显示的主题保持当前版本")
            loadLibrary()
        }
    }
    private fun applyTheme(metadata: ThemeMetadata){actions.apply(metadata){startOutput();loadLibrary()}}
    private fun startOutput(){
        ClusterDisplayStore(this).save(ClusterDisplayStore(this).read().copy(mode=ClusterDisplayConfiguration.OVERLAY))
        getSharedPreferences("cluster-display",0).edit().putBoolean("enabled",true).apply()
        startForegroundService(Intent(this,ClusterRenderService::class.java).setAction("apply"))
        message("主题已保存，正在等待仪表显示确认")
    }
    private fun themeMenu(entry: ThemeLibraryEntry){
        val options=if(entry.builtIn)arrayOf("另存副本","导出主题包")else arrayOf("另存副本","导出主题包","重命名","删除")
        com.galaxy.auto.ui.GalaxyDialogs.builder(this).setTitle(entry.metadata.name).setItems(options){_,which->
            when(which){
                0->nameDialog("副本名称","${entry.metadata.name} · 副本"){name->actions.duplicate(entry.metadata,name){loadLibrary()}}
                1->{pendingExport=entry.metadata;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE,entry.metadata.name.replace(Regex("[/\\\\:*?\"<>|]"),"_")+".gctheme"),EXPORT)}
                2->nameDialog("主题名称",entry.metadata.name){name->actions.rename(entry.metadata,name){loadLibrary()}}
                3->com.galaxy.auto.ui.GalaxyDialogs.builder(this).setTitle("删除主题？").setMessage(entry.metadata.name).setPositiveButton("删除"){_,_->actions.delete(entry.metadata){loadLibrary()}}.setNegativeButton("取消",null).show()
            }
        }.show()
    }
    private fun nameDialog(title: String,value: String,save: (String)->Unit){
        val input=ui.input(value)
        val dialog=com.galaxy.auto.ui.GalaxyDialogs.builder(this).setTitle(title).setView(input).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name=input.text.toString().trim()
            if(name.isEmpty()||name.length>80)input.error="名称需要1–80个字符"else{save(name);dialog.dismiss()}
        }
    }
    private fun pickTheme(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),IMPORT)}
    private fun pickAsset(font: Boolean){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType(if(font)"*/*"else"image/*").addCategory(Intent.CATEGORY_OPENABLE),ASSET)}
    @Deprecated("SAF兼容目标Android 10/11")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK){pendingAsset=null;pendingExport=null;pickingDisplayImage=false;return}
        val uri=data?.data?:return
        if(requestCode==ASSET&&pickingDisplayImage){
            pickingDisplayImage=false
            actions.asset(uri,false){name,bytes->
                actions.job({
                    val image=name.substringAfterLast('/')
                    val directory=java.io.File(filesDir,"display-backgrounds").apply {check(isDirectory||mkdirs())}
                    val target=android.util.AtomicFile(java.io.File(directory,image))
                    val stream=target.startWrite()
                    try {stream.write(bytes);target.finishWrite(stream)}catch(error: Exception){target.failWrite(stream);throw error}
                    image
                }){image->
                    displayDraft=(displayDraft?:ClusterDisplayStore(this).read()).copy(background=ClusterDisplayConfiguration.IMAGE_BACKGROUND,image=image)
                    displaySettings()
                }
            }
            return
        }
        when(requestCode){
            IMPORT->actions.import(uri){loadLibrary();message("主题包已导入")}
            EXPORT->pendingExport?.let { metadata->pendingExport=null;actions.export(uri,metadata){message("主题包已导出，包含XML与资源")} }
            ASSET->{
                val target=pendingAsset.also {pendingAsset=null}?:return
                val font=target is AssetTarget.Node&&target.property.key=="fontAsset"
                actions.asset(uri,font){name,bytes->if(session===target.session)attachAsset(target,name,bytes)}
            }
        }
    }
    private fun attachAsset(target: AssetTarget,name: String,bytes: ByteArray){
        val current=target.session;val c=current.content
        try {
            var scene=c.scene
            when(target){
                is AssetTarget.Background->{
                    val background=if(target.night)scene.document.nightBackground else scene.document.dayBackground
                    val next=background.copy(kind="image",image=name)
                    scene=scene.copy(document=if(target.night)scene.document.copy(nightBackground=next)else scene.document.copy(dayBackground=next))
                }
                is AssetTarget.Node->{
                    require(current.node(target.id)!=null)
                    val properties=mapOf(target.property.key to name)+(if(target.property.key=="fontAsset")mapOf("font" to "asset")else emptyMap())
                    val nodes=SceneTree.update(scene.document.nodes,target.id){node->if(target.night)node.copy(night=node.night+properties)else node.copy(day=node.day+properties)}
                    scene=scene.copy(document=scene.document.copy(nodes=nodes))
                }
            }
            current.change(c.copy(scene=scene,assets=c.assets+(name to bytes)));editor?.refresh()
        }catch(error: Exception){message("素材未应用，原设置保留")}
    }
    private fun fields(): List<Pair<String,String>> {
        // v4快照只带键名清单，不再带全量车辆值；编辑器下拉以目录清单为准。
        val known=VehiclePainter.labels.keys+(snapshot.getStringArray("vehicleKeys")?.toSet()?:emptySet())
        return known.sorted().map { key->"vehicle.$key" to (VehiclePainter.labels[key]?:key) }+
            listOf("media.title" to "歌曲标题","media.artist" to "歌手","media.album" to "专辑","media.elapsed" to "播放位置（毫秒）","media.duration" to "音乐时长（毫秒）",
                "navigation.distance" to "导航距离","navigation.road" to "道路名称","navigation.arrival" to "到达时间")
    }
    private fun displaySettings(){
        val prefs=getSharedPreferences("cluster-display",0)
        val display=displayDraft?:ClusterDisplayStore(this).read()
        val policy=snapshot.getBundle("projectionPolicy")?.let {ProjectionPolicy.from(it)}?:ProjectionPolicy()
        val body=ui.column(20)
        val enabled=Switch(this).apply {text="启用仪表主题";textSize=18f;setTextColor(ui.foreground);isChecked=prefs.getBoolean("enabled",true);minimumHeight=ui.dp(64)}
        body.addView(enabled)
        val outputModes=listOf(ClusterDisplayConfiguration.OVERLAY to "直接运行遮罩",ClusterDisplayConfiguration.OEM_ELEMENTS to "原厂元素替换")
        body.addView(ui.label("运行模式",18f))
        val outputMode=Spinner(this).apply {
            adapter=ArrayAdapter(this@ThemeActivity,android.R.layout.simple_spinner_dropdown_item,outputModes.map {it.second})
            setSelection(outputModes.indexOfFirst {it.first==display.mode})
        }
        body.addView(outputMode)
        val music=Switch(this).apply {text="原厂音乐区 · 四行歌词";textSize=18f;setTextColor(ui.foreground);isChecked=display.music;minimumHeight=ui.dp(64)}
        body.addView(music)
        val backgrounds=listOf(ClusterDisplayConfiguration.ORIGINAL to "原厂背景",ClusterDisplayConfiguration.IMAGE_BACKGROUND to "本地图片",ClusterDisplayConfiguration.CARPLAY_MAP to "CarPlay 地图",ClusterDisplayConfiguration.LOCAL_MAP to "本机地图")
        body.addView(ui.label("背景来源",18f))
        val background=Spinner(this).apply {
            adapter=ArrayAdapter(this@ThemeActivity,android.R.layout.simple_spinner_dropdown_item,backgrounds.map {it.second})
            setSelection(backgrounds.indexOfFirst {it.first==display.background})
        }
        body.addView(background)
        val imageButton=ui.button(if(display.image.isEmpty())"选择本地图片"else"更换本地图片"){}
        body.addView(imageButton)
        body.addView(ui.note("原厂元素显示状态以仪表实际连接为准。关闭显示会撤销本应用需求；基础能力仍由 AutoService 管理。"))
        val modes=listOf("content-aware" to "按内容动态刷新","fixed-cap" to "动态内容按帧率上限刷新")
        body.addView(ui.label("刷新策略",18f))
        val mode=Spinner(this).apply {adapter=ArrayAdapter(this@ThemeActivity,android.R.layout.simple_spinner_dropdown_item,modes.map {it.second});setSelection(modes.indexOfFirst {it.first==policy.mode})}
        body.addView(mode)
        fun number(title: String,value: Int): EditText {body.addView(ui.note(title));return ui.input(value.toString()).apply {inputType=InputType.TYPE_CLASS_NUMBER;body.addView(this)} }
        val maximum=number("输出帧率上限 · 1–60",policy.maximumFps)
        val animation=number("组件动画帧率上限",policy.animationFps)
        body.addView(ui.note("上限不代表实际显示帧率。静态画面按变化更新，地图跟随视频源；需在目标车机验证。"))
        val appearances=listOf("auto" to "跟随主题 / 系统","day" to "固定日间","night" to "固定夜间")
        val appearance=Spinner(this).apply {adapter=ArrayAdapter(this@ThemeActivity,android.R.layout.simple_spinner_dropdown_item,appearances.map {it.second});setSelection(appearances.indexOfFirst {it.first==prefs.getString("appearance","auto")}.coerceAtLeast(0))}
        body.addView(ui.label("全局仪表日夜",18f));body.addView(appearance)
        val dialog=com.galaxy.auto.ui.GalaxyDialogs.builder(this).setTitle("显示设置").setView(ScrollView(this).apply {addView(body)}).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val configuration=ClusterDisplayConfiguration(outputModes[outputMode.selectedItemPosition].first,
                    backgrounds[background.selectedItemPosition].first,music.isChecked,display.image)
                val next=ProjectionPolicy(modes[mode.selectedItemPosition].first,maximum.text.toString().toInt(),animation.text.toString().toInt())
                observer?.setProjectionPolicy(next)
                val enable=enabled.isChecked
                val dayNight=appearances[appearance.selectedItemPosition].first
                actions.job({
                    ClusterDisplayStore(this).save(configuration)
                    check(prefs.edit().putString("appearance",dayNight).putBoolean("enabled",enable).commit())
                }){
                    displayDraft=null
                    if(enable)startForegroundService(Intent(this,ClusterRenderService::class.java).setAction("reload"))else{
                        startForegroundService(Intent("theme-stop").setComponent(ComponentName(AutoContract.SERVICE_PACKAGE,AutoContract.SERVICE_CLASS)))
                        stopService(Intent(this,ClusterRenderService::class.java))
                    }
                    dialog.dismiss()
                }
            }catch(error: Exception){
                if(error.message=="cluster_image_missing")message("请先选择本地图片")
                else maximum.error="输出需为1–60，动画需为1至输出上限"
            }
        }
        imageButton.setOnClickListener {
            displayDraft=display.copy(mode=outputModes[outputMode.selectedItemPosition].first,music=music.isChecked)
            dialog.dismiss();pickingDisplayImage=true;pickAsset(false)
        }
    }
    private fun message(value: String){Toast.makeText(this,value,Toast.LENGTH_LONG).show();footer.text=value}
    override fun onConfigurationChanged(config: Configuration){
        super.onConfigurationChanged(config)
        val previousPage=page
        editor?.close();preview?.close();editor=null;preview=null;gallery=null;tabs.clear()
        ui=ClusterUi(this);buildPage();page=0;switchPage(previousPage);loadLibrary()
    }
    @Deprecated("兼容Android 10/11返回键")
    override fun onBackPressed(){leaveDraft {finish()}}
    override fun onDestroy(){++requestGeneration;editor?.close();preview?.close();observer?.close();actions.close();super.onDestroy()}
    companion object {private const val IMPORT=810;private const val EXPORT=811;private const val ASSET=812}
}
