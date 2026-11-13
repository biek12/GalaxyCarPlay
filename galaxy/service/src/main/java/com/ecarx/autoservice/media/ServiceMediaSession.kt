package com.ecarx.autoservice.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.ecarx.autoservice.ipc.ClientRegistry
import com.galaxy.auto.contract.AutoContract
import com.galaxy.auto.contract.IAutoResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * 媒体、导航、封面和原厂音乐输出的唯一所有者。
 *
 * 不保存第二份快照。原车媒体优先于 CarPlay 候选；曲目变化才换封面代次。
 * 构造不创建会话、不写文件、不调用发布。
 */
internal class ServiceMediaSession(
    private val context: Context,
    private val main: Handler,
    private val postArtwork: (Runnable) -> Unit,
    private val postCallback: (Runnable) -> Unit,
    private val clients: ClientRegistry,
    private val closed: () -> Boolean,
    private val paused: () -> Boolean,
    private val featuresRoot: () -> Boolean,
    private val automatic: () -> Boolean,
    private val onPublish: () -> Unit,
    private val onKanziPublished: (Long, Int) -> Unit,
    private val sourceFactory: ((Bundle?, Bitmap?) -> Unit) -> SourcePort = { receive ->
        NativeSource(NativeMediaSource(context, receive))
    },
) {
    /** 只隔离系统媒体会话。enabled 与 close 的时机仍由发布顺序决定。 */
    interface SourcePort : AutoCloseable {
        /** 按当前运行状态打开或关闭观察。 */
        fun enabled(value: Boolean)
    }

    private var media = Bundle()
    private var navigation = Bundle()
    private var carplayMedia = Bundle()
    private var nativeMedia: Bundle? = null
    private var nativeSource: SourcePort? = null
    private var nativeArtwork: Bitmap? = null
    private var visualMedia = Bundle()
    private var artworkVersion = 0L
    private var artworkGeneration = 0L
    private var oemArtwork: OemArtworkWriter? = null
    private var kanziMedia: KanziMediaPublisher? = null

    /** 当前原始媒体。调用方发布时再浅拷贝。 */
    fun media(): Bundle = media

    /** 当前导航。调用方发布时再浅拷贝。 */
    fun navigation(): Bundle = navigation

    /** 当前可视歌词。只有曲目匹配时才并进快照。 */
    fun visualMedia(): Bundle = visualMedia

    /** 封面文件代次。曲目变化和成功写入都会增加。 */
    fun artworkVersion(): Long = artworkVersion

    /** 缓存里是否已有当前封面文件。 */
    fun artworkReady(): Boolean = File(context.cacheDir, "artwork.png").isFile

    /**
     * 写入 CarPlay 候选。
     *
     * 已有原车媒体时不替换当前展示；原车消失后才退回这份候选。
     */
    fun publishCarPlay(safe: Bundle) {
        carplayMedia = safe
        if (nativeMedia == null) {
            selectMedia(safe)
        }
    }

    /**
     * 曲目与当前媒体一致时更新可视歌词。
     *
     * @return 是否写入并需要发布。
     */
    fun publishVisual(safe: Bundle): Boolean {
        if (safe.getString("track") != media.getString("track")) {
            return false
        }
        visualMedia = safe
        return true
    }

    /** 替换导航并交给调用方发布。 */
    fun publishNavigation(safe: Bundle) {
        navigation = safe
    }

    /**
     * 角色消失时清掉它生产的数据。
     *
     * MAP 清候选和导航，没有原车媒体时回到空媒体。VISUAL_MUSIC 只清可视歌词。
     */
    fun clearRole(role: String) {
        if (role == AutoContract.MAP) {
            carplayMedia = Bundle()
            navigation = Bundle()
            if (nativeMedia == null) {
                selectMedia(Bundle())
            }
        }
        if (role == AutoContract.VISUAL_MUSIC) {
            visualMedia = Bundle()
        }
    }

    /**
     * 接收封面 FD。
     *
     * 在调用线程 dup 普通文件，并在 finally 关闭原 FD。main 上再核对关闭、曲目和 MAP 租约。
     */
    fun receiveArtwork(
        owner: IBinder,
        calling: Int,
        fd: ParcelFileDescriptor,
        expectedTrack: String?,
    ) {
        val duplicate =
            try {
                check(OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode)) { "artwork_requires_regular_file" }
                ParcelFileDescriptor.dup(fd.fileDescriptor)
            } finally {
                fd.close()
            }
        main.post {
            if (closed() ||
                (expectedTrack != null && expectedTrack != media.getString("track")) ||
                runCatching { clients.require(owner, calling).role == AutoContract.MAP }.getOrDefault(false).not()
            ) {
                duplicate.close()
                return@post
            }
            val ticket = artworkGeneration
            postArtwork(
                Runnable {
                    try {
                        val bytes =
                            ParcelFileDescriptor.AutoCloseInputStream(duplicate).use {
                                it.readBytesBounded(
                                    2 * 1024 * 1024,
                                )
                            }
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        check(options.outWidth in 1..4096 && options.outHeight in 1..4096)
                        main.post {
                            if (!closed() && ticket == artworkGeneration) {
                                val file = AtomicFile(File(context.cacheDir, "artwork.png"))
                                val output = file.startWrite()
                                try {
                                    output.write(bytes)
                                    file.finishWrite(output)
                                    ++artworkVersion
                                    onPublish()
                                } catch (
                                    error: Exception,
                                ) {
                                    file.failWrite(output)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        runCatching { duplicate.close() }
                    }
                },
            )
        }
    }

    /**
     * 准备原厂封面。
     *
     * 调用线程检查普通文件并 dup，finally 关闭原 FD。main 上的平台和租约守卫失败时关闭 dup 并回 artwork_unavailable。
     */
    fun prepareOemArtwork(
        owner: IBinder,
        calling: Int,
        fd: ParcelFileDescriptor,
        result: IAutoResult,
    ) {
        val duplicate =
            try {
                check(OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode))
                ParcelFileDescriptor.dup(fd.fileDescriptor)
            } finally {
                fd.close()
            }
        main.post {
            if (closed() || paused() || !featuresRoot() || runCatching { clients.require(owner, calling) }.isFailure) {
                duplicate.close()
                postCallback(
                    Runnable {
                        runCatching { result.onResult(Bundle().apply { putString("error", "artwork_unavailable") }) }
                    },
                )
                return@post
            }
            val writer = oemArtwork ?: OemArtworkWriter(context).also { oemArtwork = it }
            writer.write(duplicate) { response -> postCallback(Runnable { runCatching { result.onResult(response) } }) }
        }
    }

    /** 未关闭且还没有源时创建一次，再按原条件启用。 */
    fun ensureNative() {
        if (!closed() && nativeSource == null) {
            nativeSource = sourceFactory(::nativeMusic)
        }
        nativeSource?.enabled(!closed() && !paused() && automatic())
    }

    /**
     * 需要音乐输出且还没有发布器时创建 Kanzi。
     *
     * published 回连当前 OEM hook。随后按原顺序 update。
     */
    fun updateKanzi(
        snapshot: Bundle,
        musicWanted: Boolean,
    ) {
        if (musicWanted && kanziMedia == null) {
            kanziMedia = KanziMediaPublisher(context, published = onKanziPublished)
        }
        kanziMedia?.update(snapshot, musicWanted)
    }

    /** 关闭原厂封面写入器。重复调用只保持已关闭。 */
    fun closeOemArtwork() {
        oemArtwork?.close()
        oemArtwork = null
    }

    /** 关闭原车媒体源。不关闭借用的 artwork worker。 */
    fun closeNativeSource() {
        nativeSource?.close()
        nativeSource = null
    }

    /** 关闭 Kanzi 发布器。 */
    fun closeKanzi() {
        kanziMedia?.close()
        kanziMedia = null
    }

    private fun selectMedia(value: Bundle) {
        if (media.getString("track") != value.getString("track")) {
            ++artworkGeneration
            visualMedia = Bundle()
            File(context.cacheDir, "artwork.png").delete()
            ++artworkVersion
        }
        val rawLines = value.getStringArrayList("lyrics").orEmpty()
        if (rawLines.isNotEmpty() && value.getLongArray("lyricTimes")?.size == rawLines.size) {
            visualMedia = Bundle()
        }
        media =
            AutoContract.sanitizeMedia(value).apply {
                val now = SystemClock.elapsedRealtime()
                putLong("receivedAt", getLong("sampledAt").takeIf { it in 1..now } ?: now)
            }
    }

    private fun nativeMusic(
        value: Bundle?,
        bitmap: Bitmap?,
    ) {
        nativeMedia = value
        val changed = media.getString("track") != (value ?: carplayMedia).getString("track")
        selectMedia(value ?: carplayMedia)
        if (value != null && bitmap != null && (changed || nativeArtwork !== bitmap)) {
            nativeArtwork = bitmap
            val ticket = artworkGeneration
            val width = bitmap.width
            val height = bitmap.height
            if (width > 0 && height > 0) {
                val scale = minOf(1f, 512f / maxOf(width, height))
                val image =
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                val owned = if (image === bitmap) image.copy(Bitmap.Config.ARGB_8888, false) else image
                postArtwork(
                    Runnable {
                        try {
                            val bytes =
                                ByteArrayOutputStream()
                                    .also { owned.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    .toByteArray()
                            main.post {
                                if (!closed() && ticket == artworkGeneration) {
                                    val file = AtomicFile(File(context.cacheDir, "artwork.png"))
                                    val output = file.startWrite()
                                    try {
                                        output.write(bytes)
                                        file.finishWrite(output)
                                        ++artworkVersion
                                        onPublish()
                                    } catch (
                                        error: Exception,
                                    ) {
                                        file.failWrite(output)
                                    }
                                }
                            }
                        } finally {
                            owned.recycle()
                        }
                    },
                )
            }
        }
        onPublish()
    }

    private class NativeSource(
        private val source: NativeMediaSource,
    ) : SourcePort {
        override fun enabled(value: Boolean) = source.enabled(value)

        override fun close() = source.close()
    }

    private fun InputStream.readBytesBounded(max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val bytes = ByteArray(8192)
        while (true) {
            val count = read(bytes)
            if (count < 0) {
                break
            }
            check(output.size() + count <= max)
            output.write(bytes, 0, count)
        }
        return output.toByteArray()
    }
}
