package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract

/** 原厂QNX媒体图读取NFS的130px JPEG；仅经AutoService既有Root接口写自有文件，不改变目录权限。 */
internal class GalaxyL6OemArtwork(private val app: Context, private val onReady: () -> Unit = {}) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var client: AutoClient? = null
    private var ready = false
    @Volatile private var closed = false
    @Volatile private var selected: Uri? = null
    @Volatile private var copied: Uri? = null
    private var pending: Pair<Uri, java.io.File>? = null
    private var busy = false
    private val files = java.util.concurrent.ConcurrentHashMap.newKeySet<java.io.File>()
    init { main.post {
        if (!closed) client = AutoClient(app, AutoContract.OBSERVER, onSnapshot = { ready = true; flush() },
            onState = { L7DebugLog.record("OemDimMedia: artwork service=$it") }).also { it.connect() }
    } }
    // 调用端为媒体串行工作线程。只对新封面解码/缩小一次；旧曲异步回执不得更新新曲路径。
    fun select(uri: Uri?) {
        if (closed || selected == uri) return
        selected = uri; copied = null
        if (uri == null) return
        try {
            val original = app.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return
            val small = Bitmap.createScaledBitmap(original, 130, 130, true)
            val file = java.io.File.createTempFile("oem-art-", ".jpg", app.cacheDir).also { files.add(it) }
            try { file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
            finally { if (small !== original) small.recycle(); original.recycle() }
            main.post {
                if (closed || selected != uri) file.delete()
                else { pending?.second?.delete(); pending = uri to file; flush() }
            }
        } catch (error: Exception) { L7DebugLog.record("OemDimMedia: artwork prepare exceptionType=${error.javaClass.simpleName}") }
    }
    fun path(uri: Uri?): String = if (uri != null && uri == copied) IMAGE else "-999"
    private fun flush() {
        if (closed || !ready || busy) return
        val (uri, file) = pending ?: return
        pending = null; busy = true
        // 文件名由本App生成，无用户Shell输入；固定目标仅为本App的一张有界缓存图。
        val command = "cat '${file.absolutePath}' > '$DIRECTORY/galaxy-carplay-artwork.tmp' && chmod 644 '$DIRECTORY/galaxy-carplay-artwork.tmp' && mv '$DIRECTORY/galaxy-carplay-artwork.tmp' '$DIRECTORY/galaxy-carplay-artwork.jpg'"
        client?.executeShell("ROOT", command, 5000) { result ->
            busy = false; file.delete(); files.remove(file)
            val ok = result.getInt("exitCode", -1) == 0 && !result.getBoolean("timedOut") && !result.containsKey("error")
            if (!closed && selected == uri && ok) { copied = uri; onReady() }
            if (!closed) { L7DebugLog.record("OemDimMedia: artwork copied=$ok"); flush() }
        }
    }
    override fun close() {
        closed = true; selected = null; copied = null
        files.forEach { it.delete() }; files.clear()
        main.post { pending?.second?.delete(); pending = null; client?.close(); client = null }
    }
    private companion object {
        const val DIRECTORY = "/data/vendor/nfs/shared/images"
        const val IMAGE = "/images/galaxy-carplay-artwork.jpg"
    }
}
