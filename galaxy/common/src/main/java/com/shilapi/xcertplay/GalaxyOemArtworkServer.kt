package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** 给原车Android媒体服务提供回环HTTP封面；只服务本代最多3张JPEG，不暴露文件或目录。
 * 对照jiopia/DiPlay-ECARX的本机URL方式；QNX仍使用L6原厂NFS图片契约，不把127.0.0.1发给QNX。
 */
internal class GalaxyOemArtworkServer(private val app: Context,
    private val jpeg: (Uri) -> ByteArray? = { readCover(app, it) }) : AutoCloseable {
    private data class Image(val source: Uri, val path: String, val bytes: ByteArray)
    private val images = ArrayDeque<Image>()
    private var server: ServerSocket? = null
    @Volatile private var closed = false
    @Volatile private var currentSocket: Socket? = null
    @Volatile private var served = 0L
    private val prefix = "/cover/${UUID.randomUUID()}/"
    private var revision = 0L

    @Synchronized fun url(uri: Uri?): Uri? {
        if (closed || uri == null) return null
        if (uri.scheme != "content" || uri.authority != "${app.packageName}.media-artwork") return null
        images.firstOrNull { it.source == uri }?.let { return address(it.path) }
        try {
            val bytes = jpeg(uri)?.takeIf { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES } ?: return null
            if (server == null) {
                val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
                server = listener
                Thread({ serve(listener) }, "galaxy-oem-cover").apply { isDaemon = true; start() }
            }
            val image = Image(uri, "$prefix${++revision}.jpg", bytes)
            images.addLast(image)
            while (images.size > 3) images.removeFirst()
            return address(image.path)
        } catch (error: Exception) {
            L7DebugLog.record("OemArtwork: prepare exceptionType=${error.javaClass.simpleName}")
            return null
        }
    }

    private fun address(path: String): Uri = Uri.parse("http://127.0.0.1:${requireNotNull(server).localPort}$path")

    private fun serve(listener: ServerSocket) {
        while (!closed) try {
            listener.accept().use { socket ->
                currentSocket = socket
                if (closed) return@use
                socket.soTimeout = 1500
                val input = socket.getInputStream()
                val header = ByteArrayOutputStream()
                var complete = false
                while (header.size() < 8192) {
                    val value = input.read()
                    if (value < 0) break
                    header.write(value)
                    // 仅识别终止符，避免为每个字节复制整个缓冲。
                    if (value == 10 && header.size() >= 4) {
                        val data = header.toByteArray()
                        val end = data.size
                        if (data[end - 4] == 13.toByte() && data[end - 3] == 10.toByte() && data[end - 2] == 13.toByte()) {
                            complete = true; break
                        }
                    }
                }
                val request = if (complete) header.toString("US-ASCII").substringBefore("\r\n").split(' ') else emptyList()
                val image = if (request.size == 3 && request[0] in setOf("GET", "HEAD")) synchronized(this) {
                    images.firstOrNull { it.path == request[1] }
                } else null
                val output = socket.getOutputStream()
                val status = if (image == null) "404 Not Found" else "200 OK"
                output.write(("HTTP/1.1 $status\r\nContent-Type: image/jpeg\r\n" +
                    "Content-Length: ${image?.bytes?.size ?: 0}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                if (image != null && request[0] == "GET") { output.write(image.bytes); served++ }
                output.flush()
            }
        } catch (error: Exception) {
            if (!closed) L7DebugLog.record("OemArtwork: request exceptionType=${error.javaClass.simpleName}")
        } finally { currentSocket = null }
    }

    @Synchronized fun diagnostics(): Map<String, Any> = mapOf("httpReady" to (server != null && !closed),
        "httpImages" to images.size, "httpReads" to served)

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        runCatching { currentSocket?.close() }
        runCatching { server?.close() }
        server = null
        images.clear()
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        fun readCover(app: Context, uri: Uri): ByteArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
            val original = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val scale = minOf(1.0, 512.0 / maxOf(original.width, original.height))
            val small = Bitmap.createScaledBitmap(original, maxOf(1, (original.width * scale).toInt()),
                maxOf(1, (original.height * scale).toInt()), true)
            return try { ByteArrayOutputStream().use { output ->
                check(small.compress(Bitmap.CompressFormat.JPEG, 85, output)); output.toByteArray()
            } } finally { if (small !== original) small.recycle(); original.recycle() }
        }
    }
}
