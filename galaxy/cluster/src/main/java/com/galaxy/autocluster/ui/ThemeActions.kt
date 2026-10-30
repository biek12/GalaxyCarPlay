package com.galaxy.autocluster.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.util.LruCache
import android.widget.ImageView
import com.galaxy.autocluster.theme.*
import java.io.Closeable
import java.io.File
import java.util.UUID

/** 库/SAF文件操作只在IO线程执行；Activity退出取消FD和回调，保存失败不覆盖当前草稿。 */
internal class ThemeActions(context: Context,private val failure: (String)->Unit): Closeable {
    data class SaveResult(val theme: ThemePackage,val applied: Boolean)
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val thread=HandlerThread("Cluster-library-io").apply { start() }
    private val io=Handler(thread.looper)
    private val store by lazy { ThemeStore(File(app.filesDir,"themes"),BuiltinThemes.packages()) }
    private val thumbnails=object: LruCache<String,Bitmap>(4096){override fun sizeOf(key: String,value: Bitmap)=value.byteCount/1024}
    @Volatile private var closed=false
    @Volatile private var document: ParcelFileDescriptor?=null
    @Volatile private var cancellation: CancellationSignal?=null
    private var saving=false
    fun <T> job(work: ()->T,done: (T)->Unit){
        if(closed)return
        io.post { if(!closed)try{val value=work();main.post { if(!closed)done(value) }}catch(error: Exception){reportError(error)} }
    }
    fun library(done: (ThemeLibrary,ThemeMetadata?)->Unit)=job({store.library() to (store.active()?.metadata?:store.load("builtin-gt")?.metadata)}){done(it.first,it.second)}
    fun edit(id: String,done: (EditorSession)->Unit)=job({val theme=requireNotNull(store.load(id));EditorSession(theme,store.library().entries.first { it.metadata.id==id }.builtIn)},done)
    fun blank(done: (EditorSession)->Unit)=job({
        val theme=SceneXml.write(ThemeMetadata("theme-${UUID.randomUUID()}","未命名主题",1),ThemeScene(SceneDocument(emptyList()),SceneStyles()))
        EditorSession(theme,false,true)
    },done)
    fun save(session: EditorSession,apply: Boolean,done: (SaveResult)->Unit){
        if(saving)return
        session.finishGesture()
        val snapshot=session.content;val expected=session.expectedRevision
        val metadata=if(expected==null)ThemeMetadata("theme-${UUID.randomUUID()}",session.name,1)else session.metadata.copy(name=session.name)
        saving=true
        io.post {
            try {
                val candidate=SceneXml.write(metadata,snapshot.scene,snapshot.assets)
                val prepared=ThemePreviewImages.withPreview(app,candidate)
                val result=store.save(prepared,expected)
                val applied=apply&&runCatching { store.apply(result.metadata.id,result.metadata.revision) }.isSuccess
                main.post { saving=false;if(!closed){session.saved(result,snapshot);done(SaveResult(result,applied));if(apply&&!applied)failure("主题已保存；应用未完成，当前仪表画面保留") } }
            }catch(error: Exception){main.post { saving=false };reportError(error)}
        }
    }
    fun apply(metadata: ThemeMetadata,done: ()->Unit)=job({
        val theme=requireNotNull(store.load(metadata.id))
        ThemePreviewImages.render(app,theme) // 先验证场景/资源，再替换active快照。
        store.apply(metadata.id,metadata.revision)
    }){done()}
    fun duplicate(metadata: ThemeMetadata,name: String,done: ()->Unit)=job({store.duplicate(metadata.id,name)}){done()}
    fun rename(metadata: ThemeMetadata,name: String,done: ()->Unit)=job({store.rename(metadata.id,name,metadata.revision)}){done()}
    fun delete(metadata: ThemeMetadata,done: ()->Unit)=job({store.delete(metadata.id,metadata.revision)}){done()}
    fun import(uri: Uri,done: ()->Unit)=job({
        val parsed=readDocument(uri){input->ThemePackageCodec.read(input)}
        val validated=ThemePreviewImages.withPreview(app,parsed)
        store.import(validated)
    }){done()}
    fun export(uri: Uri,metadata: ThemeMetadata,done: ()->Unit)=job({
        val theme=requireNotNull(store.load(metadata.id))
        if(theme.metadata.revision!=metadata.revision)throw ThemeRevisionConflict()
        val signal=CancellationSignal().also { cancellation=it }
        val fd=requireNotNull(app.contentResolver.openFileDescriptor(uri,"wt",signal)).also { document=it }
        try{ParcelFileDescriptor.AutoCloseOutputStream(fd).use { ThemePackageCodec.write(theme,it) }}finally{document=null;cancellation=null;fd.close()}
    }){done()}
    fun asset(uri: Uri,font: Boolean,done: (String,ByteArray)->Unit)=job({
        val bytes=readDocument(uri){input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
            while(true){val count=input.read(buffer);if(count<0)break;require(out.size()+count<=ThemePackageCodec.MAX_FILE_BYTES){"theme_asset_too_large"};out.write(buffer,0,count)}
            out.toByteArray()
        }
        val header=bytes.take(12).toByteArray()
        val signature=header.take(4).toByteArray().toString(Charsets.ISO_8859_1)
        val extension=if(font)when{
            signature=="OTTO"->"otf"
            header.size>=4&&header[0]==0.toByte()&&header[1]==1.toByte()&&header[2]==0.toByte()&&header[3]==0.toByte()->"ttf"
            else->error("theme_font_format_invalid")
        }else when{
            signature=="\u0089PNG"->"png"
            header.size>=3&&header[0]==0xFF.toByte()&&header[1]==0xD8.toByte()&&header[2]==0xFF.toByte()->"jpg"
            signature=="RIFF"&&header.size>=12&&header.copyOfRange(8,12).toString(Charsets.US_ASCII)=="WEBP"->"webp"
            else->error("theme_image_format_invalid")
        }
        if(font){val file=File.createTempFile("cluster-font-check-",".$extension",app.cacheDir);try{file.writeBytes(bytes);android.graphics.Typeface.createFromFile(file)}finally{file.delete()}}
        else{
            val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth>0&&bounds.outHeight>0){"theme_image_invalid"}
        }
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        "assets/$hash.$extension" to bytes
    }){done(it.first,it.second)}
    private fun <T> readDocument(uri: Uri,read: (java.io.InputStream)->T): T {
        val signal=CancellationSignal().also { cancellation=it }
        val fd=requireNotNull(app.contentResolver.openFileDescriptor(uri,"r",signal)).also { document=it }
        return try{ParcelFileDescriptor.AutoCloseInputStream(fd).use(read)}finally{document=null;cancellation=null;fd.close()}
    }
    private class Thumbnail(val key: String,var job: Runnable?=null){@Volatile var cancelled=false}
    fun thumbnail(target: ImageView,metadata: ThemeMetadata,active: Boolean=false){
        (target.tag as? Thumbnail)?.let { it.cancelled=true;it.job?.let(io::removeCallbacks) }
        val key="${metadata.id}:${metadata.revision}:$active"
        val ticket=Thumbnail(key);target.tag=ticket
        thumbnails.get(key)?.let { target.setImageBitmap(it);return }
        target.setImageDrawable(null)
        val work=Runnable {
            if(closed||ticket.cancelled)return@Runnable
            try {
                val theme=if(active)store.active()?:store.load(metadata.id)else store.load(metadata.id)
                val image=ThemePreviewImages.render(app,requireNotNull(theme),width=640)
                main.post { if(!closed){thumbnails.put(key,image);if(target.tag===ticket)target.setImageBitmap(image)} }
            }catch(error: Exception){reportError(error)}
        }
        ticket.job=work;io.post(work)
    }
    private fun reportError(error: Exception){
        val text=when{
            error is ThemeRevisionConflict->"主题已被另一处修改，请重新打开；当前草稿已保留"
            error.message=="theme_active_select_another_first"->"请先切换到其他主题，再删除当前主题"
            error.message?.contains("budget")==true||error.message?.contains("too_large")==true->"主题资源超过允许的大小，请缩小图片后再试"
            else->"操作未完成，原主题和草稿保留（${error.javaClass.simpleName}）"
        }
        main.post { if(!closed)failure(text) }
    }
    override fun close(){
        if(closed)return;closed=true;cancellation?.cancel();runCatching { document?.close() }
        io.removeCallbacksAndMessages(null);main.removeCallbacksAndMessages(null);thumbnails.evictAll();thread.quitSafely()
    }
}
