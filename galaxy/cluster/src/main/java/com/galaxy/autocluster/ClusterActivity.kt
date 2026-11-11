package com.galaxy.autocluster

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.ClusterDisplayConfiguration
import com.galaxy.auto.ui.*
import com.galaxy.autocluster.runtime.ClusterDisplayStore
import com.galaxy.autocluster.ui.InstrumentMusicPreview
import java.io.File

/** 独立App主导航只承载仪表、主题、显示设置；服务连接设置属于AutoService。 */
class ClusterActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.galaxy.autocluster.runtime.ClusterAppearance.wrap(base))
    private lateinit var content: LinearLayout
    private lateinit var store: ClusterDisplayStore
    private var client: AutoClient?=null
    private var draft=ClusterDisplayConfiguration()
    private var snapshot=Bundle()
    private var page="home"
    private var connected=false
    private var readingImage=false
    private var imageTicket=0L
    private val io=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var preview: InstrumentMusicPreview?=null
    private var runtimeStatus: TextView?=null
    private var pendingRevision=0L
    private val navigation=linkedMapOf<String,Button>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        readingImage=state?.getBoolean("readingImage")?:false
        store=ClusterDisplayStore(this);draft=store.draft()
        buildShell();show(state?.getString("page")?:"home")
        client=AutoClient(this,AutoContract.OBSERVER,onSnapshot={value->
            snapshot=value;connected=true;preview?.snapshot(value)
            if(page in setOf("home","result"))show(page) else runtimeStatus?.text=elementStatus(page)
        },onState={state->
            if(state in setOf("service_disconnected","binding_died","service_unavailable")){connected=false;if(page in setOf("home","result"))show(page)else runtimeStatus?.text=elementStatus(page)}
        }).also{it.connect()}
        if(getSharedPreferences("cluster-display",0).getBoolean("enabled",false))
            startForegroundService(Intent(this,ClusterRenderService::class.java))
    }
    private fun dp(value: Int)=GalaxyViews.dp(this,value)
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun label(value: String,role: GalaxyTypography.Role=GalaxyTypography.Role.LABEL)=GalaxyViews.text(this,value,role)
    private fun buildShell(){
        val root=column().apply{setBackgroundColor(getColor(com.galaxy.auto.ui.R.color.galaxy_ui_background))}
        root.addView(label("AutoCluster",GalaxyTypography.Role.PAGE_TITLE).apply{setPadding(dp(24),dp(24),dp(24),dp(24))})
        val body=LinearLayout(this)
        val nav=column().apply{setPadding(dp(8),0,dp(8),0)}
        navigation.clear()
        listOf("home" to "仪表","themes" to "主题","settings" to "设置").forEach{(id,title)->
            GalaxyViews.button(this,title){show(id)}.also{navigation[id]=it;nav.addView(it,LinearLayout.LayoutParams(-1,-2))}
        }
        body.addView(nav,LinearLayout.LayoutParams(dp(100),-1))
        content=column().apply{setPadding(dp(16),0,dp(24),dp(24))}
        body.addView(ScrollView(this).apply{isFillViewport=true;addView(content)},LinearLayout.LayoutParams(0,-1,1f))
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
    }
    private fun title(value: String,back: String?=null){
        if(back!=null)content.addView(GalaxyViews.textButton(this,"‹ 返回"){show(back)}.apply{layoutParams=LinearLayout.LayoutParams(-2,-2)})
        content.addView(label(value,GalaxyTypography.Role.PAGE_TITLE))
    }
    private fun note(value: String){content.addView(label(value,GalaxyTypography.Role.DESCRIPTION).apply{setPadding(0,dp(12),0,dp(12))})}
    private fun group(build: (GalaxySettingsCard)->Unit){content.addView(GalaxySettingsCard(this).also(build),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})}
    private fun action(title: String,detail: String="",click: ()->Unit)=GalaxySettingRow(this,title,detail).apply{
        setValue("›");isFocusable=true;GalaxyStyle.rowFeedback(this);setOnClickListener{click()}
    }
    private fun button(title: String,primary: Boolean=false,click: ()->Unit){content.addView(GalaxyViews.button(this,title,primary,click),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})}
    private fun statusNote(){
        runtimeStatus=label(elementStatus(page),GalaxyTypography.Role.DESCRIPTION).also {
            it.setPadding(0,dp(12),0,dp(12));content.addView(it)
        }
    }
    private fun show(next: String){
        page=next;preview=null;runtimeStatus=null;content.removeAllViews()
        navigation.forEach{(id,button)->button.isSelected=id==if(next=="themes")"themes"else if(next=="settings")"settings"else"home";GalaxyViews.navigationStyle(button)}
        when(next){
            "home"->{
                title("我的仪表")
                val enabled=getSharedPreferences("cluster-display",0).getBoolean("enabled",false)
                val applied=store.read()
                group{card->card.addView(GalaxySettingRow(this,"当前显示",when{
                    !enabled->if(snapshot.getString("clusterElementStatus") in setOf("active","preparing","waiting-content","restoring"))"正在恢复原厂显示" else "原厂仪表"
                    !connected->"等待服务连接"
                    applied.mode==ClusterDisplayConfiguration.OEM_ELEMENTS->elementStatus()
                    snapshot.getBoolean("qnxReady")->"自定义主题通道已连接，显示结果以仪表为准"
                    else->"自定义主题 · 等待仪表通道"
                }))}
                group{it.addView(action("原厂增强","更换背景、增强音乐卡片"){draft=store.draft().copy(mode=ClusterDisplayConfiguration.OEM_ELEMENTS);show("oem")})}
                group{it.addView(action("自定义仪表","选择主题，编辑整套仪表"){openThemes()})}
                if(enabled)button("恢复原厂显示"){show("restore")}
            }
            "oem"->{
                title("原厂增强","home");note("保留原厂仪表，音乐开关与背景选择直接生效")
                val elements=store.elements()
                group{card->
                    card.addView(action("音乐卡片",if(elements.music)"已开启 · 紧凑头部 · 四行歌词"else"已关闭 · 保留原厂音乐"){show("music")})
                    card.addView(action("背景",backgroundName(elements.background)){show("background")})
                }
                statusNote()
            }
            "music"->{
                title("音乐卡片","oem")
                group{it.addView(GalaxyViews.switchRow(this,"替换原厂音乐卡片","开启即提交，关闭即恢复原厂音乐",store.elements().music){value->
                    submitElements(store.elements().copy(music=value,image=store.elements().image.ifEmpty{draft.image}))
                })}
                statusNote()
                preview=InstrumentMusicPreview(this).also{it.snapshot(snapshot);content.addView(it,LinearLayout.LayoutParams(-1,dp(350)))}
                note("仪表上的音乐卡片预览")
                group{card->card.addView(GalaxySettingRow(this,"歌词行数").apply{setValue("四行")});card.addView(GalaxySettingRow(this,"长句").apply{setValue("当前句自动滚动")})}
                button("返回原厂增强"){show("oem")}
            }
            "background"->{
                title("仪表背景","oem")
                val elements=store.elements()
                val sources=listOf(ClusterDisplayConfiguration.ORIGINAL,ClusterDisplayConfiguration.IMAGE_BACKGROUND,ClusterDisplayConfiguration.CARPLAY_MAP,ClusterDisplayConfiguration.LOCAL_MAP)
                group{card->sources.forEach{source->card.addView(action(backgroundName(source),if(source==elements.background)"已选择"else""){
                    if(source==ClusterDisplayConfiguration.IMAGE_BACKGROUND&&draft.image.isEmpty())pickImage()
                    else submitElements(store.elements().copy(background=source,image=draft.image))
                })}}
                statusNote()
                when(elements.background){
                    ClusterDisplayConfiguration.IMAGE_BACKGROUND->{note("已选择本地图片，完整保留画面。");button("更换图片"){pickImage()}}
                    ClusterDisplayConfiguration.CARPLAY_MAP->note("连接 iPhone 后显示完整 CarPlay 地图，等待来源与首帧。")
                    ClusterDisplayConfiguration.LOCAL_MAP->note("使用车机地图画面，实际来源状态以仪表回执为准。")
                }
                if(elements.background!=ClusterDisplayConfiguration.IMAGE_BACKGROUND&&draft.image.isNotEmpty())
                    note("已有本地图片，选择“本地图片”即可提交替换。")
                if(elements.background!=ClusterDisplayConfiguration.IMAGE_BACKGROUND)button("选择新图片"){pickImage()}
                button("返回原厂增强"){show("oem")}
            }
            "themes"->{title("自定义主题");group{it.addView(action("我的主题","选择、编辑和导入主题"){openThemes()})}}
            "settings"->{
                title("显示设置")
                val prefs=getSharedPreferences("cluster-display",0)
                val values=listOf("auto","day","night");val labels=listOf("跟随系统","日间","夜间")
                group{card->card.addView(action("日夜外观",labels[values.indexOf(prefs.getString("appearance","auto")).coerceAtLeast(0)]){
                    GalaxySelection.select(this,"日夜外观",labels,values.indexOf(prefs.getString("appearance","auto")).coerceAtLeast(0),"确定"){index->
                        prefs.edit().putString("appearance",values[index]).apply()
                        if(prefs.getBoolean("enabled",false))startForegroundService(Intent(this,ClusterRenderService::class.java).setAction("reload"))
                        recreate()
                    }
                })}
            }
            "restore"->{title("恢复原厂显示","home");note("停止本 App 的仪表输出，恢复原厂背景和音乐卡片。你的主题和配置仍保留。")
                button("恢复原厂",true){getSharedPreferences("cluster-display",0).edit().putBoolean("enabled",false).commit();startForegroundService(Intent(this,ClusterRenderService::class.java).setAction("stop"));show("result")}
                button("取消"){show("home")}}
            "result"->{title("仪表显示状态");note(if(getSharedPreferences("cluster-display",0).getBoolean("enabled",false))elementStatus()else if(snapshot.getString("clusterElementStatus")=="restored")"已确认恢复原厂显示" else "已撤销本 App 显示需求，等待仪表恢复确认。")
                button("返回仪表",true){show("home")}}
        }
    }
    private fun elementStatus(element: String=""): String=when{
        !store.enabled||store.read().mode!=ClusterDisplayConfiguration.OEM_ELEMENTS->when(snapshot.getString("clusterElementStatus")){
            "restoring","active","preparing","waiting-content"->"正在恢复原厂显示"
            "restored"->"已确认恢复原厂显示"
            else->"原厂增强已关闭"
        }
        element=="music"&&!store.elements().music->"音乐卡片替换已关闭"
        element=="background"&&store.elements().background==ClusterDisplayConfiguration.ORIGINAL->"使用原厂背景"
        !connected->"等待服务连接"
        !snapshot.getBoolean("automatic",true)->"AutoService 已暂停，显示选择已保留"
        !snapshot.getBundle("features").let{it?.getBoolean("rootEnabled",true)?:true}->"基础能力已关闭，请在 AutoService 中开启"
        snapshot.getString("clusterConfigurationStatus")=="pending"->"正在准备背景图片"
        snapshot.getString("clusterConfigurationStatus")=="failed"->"背景图片未能应用，显示选择已保留"
        snapshot.getLong("clusterElementRevision")<pendingRevision->"正在提交仪表配置"
        snapshot.getBundle("clusterElements")?.let{ClusterDisplayConfiguration.from(it)}!=store.elements()->"正在提交仪表配置"
        element=="music"&&snapshot.getBundle("clusterElementRuntime")?.getBoolean("musicMounted")==true->"音乐卡片已替换"
        element=="background"&&snapshot.getBundle("clusterElementRuntime")?.getBoolean("backgroundMounted")==true->"仪表背景已替换"
        snapshot.getString("clusterElementStatus")=="active"->"原厂增强运行中"
        snapshot.getString("clusterElementStatus")=="failed"->"本次应用未完成，配置已保留"
        snapshot.getString("clusterElementStatus")=="waiting-content"->"仪表已连接，等待媒体区域或背景就绪"
        snapshot.getString("clusterElementStatus")=="waiting-feedback"->"等待仪表状态回执"
        snapshot.getString("clusterElementStatus")=="restoring"->"正在恢复原厂显示"
        snapshot.getString("clusterElementStatus")=="restored"->"已确认恢复原厂显示"
        snapshot.getString("clusterElementStatus")=="preparing"->"正在应用仪表配置"
        snapshot.getString("clusterElementStatus")=="waiting-root"->"正在等待 Root 服务就绪"
        else->"配置已保存，等待仪表确认"
    }
    private fun submitElements(value: ClusterDisplayConfiguration){
        runCatching {
            store.submitElements(value);draft=value
            val acknowledged=snapshot.getBundle("clusterElements")?.let{ClusterDisplayConfiguration.from(it)}
            pendingRevision=snapshot.getLong("clusterElementRevision")+if(acknowledged==value)0 else 1
            startForegroundService(Intent(this,ClusterRenderService::class.java).setAction(if(value.needsElements)"apply"else"stop"))
        }.onFailure{Toast.makeText(this,"仪表配置未能提交，请重试",Toast.LENGTH_LONG).show()}
        show(page)
    }
    private fun openThemes(){startActivity(Intent(this,ThemeActivity::class.java))}
    private fun backgroundName(value: String)=when(value){ClusterDisplayConfiguration.IMAGE_BACKGROUND->"本地图片";ClusterDisplayConfiguration.CARPLAY_MAP->"CarPlay 地图";ClusterDisplayConfiguration.LOCAL_MAP->"本机地图";else->"原厂背景"}
    private fun pickImage(){readingImage=true;startActivityForResult(Intent(this,BackgroundImagePicker::class.java),41)}
    @Deprecated("兼容Android 10/11")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=41||!readingImage)return
        readingImage=false
        if(resultCode!=RESULT_OK)return
        val selected=data?.getStringExtra("imagePath")?.let(::File)?:return
        val ticket=++imageTicket
        io.execute{
            val result=runCatching{
                require(selected.isFile && selected.length() in 1..16L*1024*1024)
                val bytes=selected.inputStream().use{input->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=16*1024*1024);output.write(buffer,0,n)};output.toByteArray()
                }
                val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                require(bounds.outWidth in 1..16384&&bounds.outHeight in 1..16384)
                val options=android.graphics.BitmapFactory.Options()
                while(bounds.outWidth/options.inSampleSize.coerceAtLeast(1)>3840||bounds.outHeight/options.inSampleSize.coerceAtLeast(1)>2160)options.inSampleSize=options.inSampleSize.coerceAtLeast(1)*2
                val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?:error("image_invalid")
                bitmap.recycle()
                val extension=when(bounds.outMimeType){"image/jpeg"->"jpg";"image/webp"->"webp";else->"png"}
                val name="background-${java.util.UUID.randomUUID()}.$extension"
                val dir=File(filesDir,"display-backgrounds").apply{mkdirs()}
                val saved=android.util.AtomicFile(File(dir,name));val output=saved.startWrite()
                try{output.write(bytes);saved.finishWrite(output)}catch(error:Exception){saved.failWrite(output);throw error}
                name
            }
            runOnUiThread{if(!isDestroyed&&ticket==imageTicket)result.onSuccess{name->submitElements(store.elements().copy(background=ClusterDisplayConfiguration.IMAGE_BACKGROUND,image=name))}.onFailure{Toast.makeText(this,"图片无法读取，请选择其他图片",Toast.LENGTH_LONG).show()}}
        }
    }
    override fun onSaveInstanceState(state: Bundle){state.putString("page",page);state.putBoolean("readingImage",readingImage);super.onSaveInstanceState(state)}
    override fun onConfigurationChanged(config: Configuration){super.onConfigurationChanged(config);val current=page;buildShell();show(current)}
    @Deprecated("兼容Android 10/11") override fun onBackPressed(){when(page){"home"->super.onBackPressed();"music","background"->show("oem");else->show("home")}}
    override fun onDestroy(){++imageTicket;client?.close();io.shutdownNow();super.onDestroy()}
}
