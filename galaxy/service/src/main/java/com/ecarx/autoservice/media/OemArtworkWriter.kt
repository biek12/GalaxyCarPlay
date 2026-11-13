package com.ecarx.autoservice.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.ecarx.autoservice.SerialWorker
import com.ecarx.autoservice.ServiceTrace
import com.ecarx.autoservice.platform.foundation.PlatformAccess
import com.ecarx.autoservice.startup.AutoStartup
import java.io.ByteArrayOutputStream
import java.io.File

/** 语义化封面写入只接受图像FD；目标、权限、缩放与原子替换全部由服务管理。 */
internal class OemArtworkWriter(
    private val context: Context,
) : AutoCloseable {
    private val worker = SerialWorker("AutoService-oem-artwork")
    private val access = PlatformAccess(context)

    @Volatile private var closed = false

    /**
     * 在自有 worker 上读取图像 FD、缩放到 130 并经 Root 原子替换原厂封面。
     *
     * 调用方交出 FD。成功、失败和关闭都在本方法里关闭它，结果只在尚未 close 时回给 [result]。
     */
    fun write(fd: ParcelFileDescriptor, result: (Bundle) -> Unit) {
        worker.post {
            val value = Bundle()
            var file: File? = null
            try {
                check(!closed && AutoStartup.enabled(context))
                val bytes =
                    ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <
                                0
                            ) {
                                break
                            }
                            require(output.size() + n <= 2 * 1024 * 1024)
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192)
                val options =
                    BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) /
                            inSampleSize >
                            512
                        ) {
                            inSampleSize *= 2
                        }
                    }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("artwork_invalid")
                try {
                    val small = Bitmap.createScaledBitmap(bitmap, 130, 130, true)
                    try {
                        file =
                            File.createTempFile("oem-artwork-", ".jpg", context.cacheDir).apply {
                                outputStream().use { require(small.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
                            }
                    } finally {
                        if (small !==
                            bitmap
                        ) {
                            small.recycle()
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                val dir = "/data/vendor/nfs/nfs_ota/autoservice-media"
                val response =
                    access.executeShell(
                        "mkdir -p '$dir' && chmod 755 '$dir' && cat '${file!!.absolutePath}' > '$dir/oem-artwork.tmp' && chmod 644 '$dir/oem-artwork.tmp' && mv '$dir/oem-artwork.tmp' '$dir/oem-artwork.jpg'",
                        5000,
                        "ROOT",
                    )
                check(response.optInt("exitCode", -1) == 0 && !response.optBoolean("timedOut"))
                value.putString("path", "/otaupdate/autoservice-media/oem-artwork.jpg")
                value.putBoolean("written", true)
            } catch (
                error: Exception,
            ) {
                value.putString("error", error.javaClass.simpleName)
                ServiceTrace.event("oem_artwork_write", error)
            } finally {
                runCatching { fd.close() }
                file?.delete()
            }
            if (!closed)result(value)
        }
    }

    /** 拒绝后续写入，并关闭平台访问和 worker。已经排队的任务仍会走完 finally。 */
    override fun close() {
        closed = true
        access.close()
        worker.close()
    }
}
