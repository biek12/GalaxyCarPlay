package com.galaxy.autocluster

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.widget.*
import com.galaxy.auto.ui.*
import java.io.File
import java.util.concurrent.Executors

/** 车机自带图片浏览器，不依赖固件中缺失的 DocumentsUI/图库 Activity。 */
class BackgroundImagePicker : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.galaxy.autocluster.runtime.ClusterAppearance.wrap(base))
    private lateinit var content: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private var ticket = 0L
    private var directory: File? = null
    private var page = 0
    private val imageTypes = setOf("png", "jpg", "jpeg", "webp")
    private val permission get() = if(Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        directory = state?.getString("directory")?.let(::File)
        page = state?.getInt("page") ?: 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
            setBackgroundColor(getColor(com.galaxy.auto.ui.R.color.galaxy_ui_background))
        }
        root.addView(GalaxyViews.textButton(this,"‹ 返回"){ if(directory == null)finish()else showSources() })
        root.addView(GalaxyViews.text(this,"选择背景图片",GalaxyTypography.Role.PAGE_TITLE))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) },LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        directory?.let { browse(it,page) } ?: showSources()
    }

    private fun dp(value: Int) = GalaxyViews.dp(this,value)
    private fun note(text: String) { content.addView(GalaxyViews.text(this,text,GalaxyTypography.Role.DESCRIPTION).apply { setPadding(0,dp(12),0,dp(12)) }) }
    private fun row(title: String,description: String="",click: () -> Unit) {
        content.addView(GalaxySettingRow(this,title,description).apply {
            setValue("›");setOnClickListener { click() };GalaxyStyle.rowFeedback(this)
        },LinearLayout.LayoutParams(-1,-2))
    }

    private fun showSources() {
        ++ticket;directory=null;page=0;content.removeAllViews()
        note("选择图片后可预览，确认后用于仪表背景。支持 PNG、JPEG、WebP。")
        row("已导入的图片","包括已传到车机的背景"){ browse(File(filesDir,"display-backgrounds")) }
        if(checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            row("允许读取本机和 USB 图片","授权后可浏览本机存储和已挂载的 USB 存储") { requestPermissions(arrayOf(permission),51) }
            return
        }
        val primary=Environment.getExternalStorageDirectory()
        row("本机图片","下载、相册及其他图片文件夹") { browse(primary) }
        getSystemService(StorageManager::class.java).storageVolumes.filter { !it.isPrimary && it.state == Environment.MEDIA_MOUNTED }
            .forEach { volume -> volume.directory?.let { folder ->
                row(volume.getDescription(this),"USB / 外部存储") { browse(folder) }
            } }
    }

    private fun browse(folder: File,index: Int=0) {
        directory=folder;page=index;val current=++ticket
        content.removeAllViews();note("正在读取图片…")
        io.execute {
            val result=runCatching {
                val children=folder.listFiles() ?: error("directory_unreadable")
                children.filter { !it.name.startsWith('.') && (it.isDirectory || it.extension.lowercase() in imageTypes) }
                    .sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
            runOnUiThread {
                if(isDestroyed || current != ticket)return@runOnUiThread
                content.removeAllViews()
                row("存储位置","重新选择本机、USB 或已导入图片") { showSources() }
                note(folder.name.ifEmpty { "本机存储" })
                val roots=listOfNotNull(File(filesDir,"display-backgrounds"),Environment.getExternalStorageDirectory()) +
                    getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }
                if(roots.none { it.absolutePath==folder.absolutePath })folder.parentFile?.let { parent ->row("上一级") { browse(parent) } }
                result.onSuccess { entries ->
                    if(entries.isEmpty())note("此文件夹没有图片或子文件夹。")
                    entries.drop(index*60).take(60).forEach { file ->
                        row(file.name,if(file.isDirectory)"文件夹"else"${(file.length()+1023)/1024} KB") {
                            if(file.isDirectory)browse(file)else preview(file)
                        }
                    }
                    if(index>0)row("上一页") { browse(folder,index-1) }
                    if(entries.size>(index+1)*60)row("下一页") { browse(folder,index+1) }
                }.onFailure { note("无法读取此文件夹，请检查图片读取权限或 USB 连接。") }
            }
        }
    }

    private fun preview(file: File) {
        val current=++ticket
        io.execute {
            val result=runCatching {
                require(file.isFile && file.length() in 1..16L*1024*1024) { "image_size" }
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(file.path,bounds)
                require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384)
                val options=BitmapFactory.Options().apply { inSampleSize=1 }
                while(bounds.outWidth/options.inSampleSize>1280 || bounds.outHeight/options.inSampleSize>720)options.inSampleSize*=2
                BitmapFactory.decodeFile(file.path,options) ?: error("image_decode")
            }
            runOnUiThread {
                if(isDestroyed || current != ticket) { result.getOrNull()?.recycle();return@runOnUiThread }
                result.onSuccess { bitmap -> showPreview(file,bitmap) }
                    .onFailure { Toast.makeText(this,"无法读取图片，请选择 16 MB 以内的有效图片",Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showPreview(file: File,bitmap: Bitmap) {
        val image=ImageView(this).apply { setImageBitmap(bitmap);scaleType=ImageView.ScaleType.FIT_CENTER }
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;addView(image,LinearLayout.LayoutParams(-1,dp(220))) }
        GalaxyDialogs.builder(this).setTitle(file.name).setView(body)
            .setNegativeButton("取消",null)
            .setPositiveButton("使用此图片") { _,_ ->
                setResult(RESULT_OK,Intent().putExtra("imagePath",file.absolutePath));finish()
            }.setOnDismissListener { image.setImageDrawable(null);bitmap.recycle() }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grants: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grants)
        if(requestCode==51) {
            if(grants.firstOrNull()!=PackageManager.PERMISSION_GRANTED)Toast.makeText(this,"未获得图片权限，仍可使用已导入的图片",Toast.LENGTH_LONG).show()
            showSources()
        }
    }
    override fun onSaveInstanceState(state: Bundle) { state.putString("directory",directory?.absolutePath);state.putInt("page",page);super.onSaveInstanceState(state) }
    @Deprecated("兼容Android 10/11") override fun onBackPressed() { if(directory==null)super.onBackPressed()else showSources() }
    override fun onDestroy() { ++ticket;io.shutdownNow();super.onDestroy() }
}
