package com.galaxy.autocluster.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 本地图片导入：从旧 `ClusterActivity.onActivityResult` 原样迁移的校验、有界读、
 * 解码验证与 AtomicFile 保存；只管文件导入和结果交付，不读配置、不发服务 Intent。
 *
 * 调度合同：
 * - 容量 1 的最新待执行请求：新请求直接替换尚未执行的旧请求，最新选择优先；
 * - 最多一个已排入/正在运行的调度 pump：`importImage` 只替换 pending，
 *   仅当 pump 未排入时向 executor 提交一次；pump 循环接续最新请求直至无 pending；
 * - 已在运行的旧任务允许结束，但其回调代次已失效，不回主线程；
 * - [close] 释放 pending、使一切迟到回调失效并停止自有队列，
 *   主线程回调不再触 View / 写配置 / 启动服务。
 *
 * 线程/所有者：`importImage` 主线程调用；校验与 IO 全部在 worker（pump 锁外执行）；
 * 结果经主线程 [Handler] 交付。异常保留原因链随 [Result] 交给调用方展示，
 * 本类不记录完整私有路径或载荷。
 * `executor` 参数仅供测试用可控调度驱动已排队任务（只替换调度边界），
 * 生产路径使用默认的单线程 [ExecutorService]。
 */
internal class BackgroundImageImport(
    context: Context,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val onResult: (Result<String>) -> Unit,
) {
    private val filesDir = context.filesDir
    private val lock = ReentrantLock()

    /** 容量 1 的待执行请求：新请求直接替换未执行的旧请求。 */
    private var pending: Runnable? = null

    /** pump 已排入或正在运行；与 pending 同锁保护，避免重复提交或丢唤醒。 */
    private var pumpScheduled = false

    /** 代次由本类单独拥有：每个请求一代，仅最新请求的回调有效。 */
    @Volatile
    private var generation = 0L

    @Volatile
    private var closed = false

    /** 提交一次导入请求；只替换 pending，pump 未排入时才向 executor 提交一次。 */
    fun importImage(source: File) {
        if (closed) return
        val ticket = ++generation
        val job = Runnable { runJob(source, ticket) }
        val needsSubmit =
            lock.withLock {
                pending = job
                val submit = !pumpScheduled
                pumpScheduled = true
                submit
            }
        if (!needsSubmit) return
        try {
            executor.execute(pump)
        } catch (error: Throwable) {
            // 提交被拒绝：复原本轮调度标志并保留原异常，不留永久“已排入”的假状态。
            lock.withLock { pumpScheduled = false }
            throw error
        }
    }

    /** 关闭：释放 pending、停自有队列、使回调失效；已提交未执行的任务不再运行。 */
    fun close() {
        if (closed) return
        closed = true
        ++generation
        lock.withLock { pending = null }
        (executor as? ExecutorService)?.shutdownNow()
    }

    /**
     * 唯一调度 pump：同一锁内取走并清空最新 pending，锁外执行原导入；
     * 运行期间新请求只覆盖 pending，循环接续最新一份；找不到 pending 或已关闭时
     * 在锁内复原调度标志并退出。
     */
    private val pump =
        Runnable {
            while (true) {
                val job =
                    lock.withLock {
                        val next = pending
                        pending = null
                        if (next == null || closed) {
                            pumpScheduled = false
                            null
                        } else {
                            next
                        }
                    }
                if (job == null) return@Runnable
                job.run()
            }
        }

    /** 在 worker 执行导入；结束后按代次决定是否回主线程交付结果。 */
    private fun runJob(
        source: File,
        ticket: Long,
    ) {
        val result = runCatching { validateAndSave(source) }
        if (closed || ticket != generation) return
        mainHandler.post {
            if (closed || ticket != generation) return@post
            onResult(result)
        }
    }

    /**
     * 校验、有界读取、解码验证并保存原始字节；参数与旧 `onActivityResult` 完全一致：
     * 文件 1..16MiB，8192 字节 buffer 逐次有界读且读中同样限制 16MiB，
     * 边界两维 1..16384，inSampleSize 按 2 倍递增至解码不超过 3840×2160，
     * 解码验证后 recycle；jpeg→jpg、webp→webp、其余沿用 png 命名；
     * 原始 bytes 由 AtomicFile 写入 `display-backgrounds/background-UUID.ext`，失败 failWrite。
     * 成功只返回文件名，不返回位图或路径。
     */
    private fun validateAndSave(source: File): String {
        require(source.isFile && source.length() in MIN_BYTES..MAX_BYTES)
        val bytes = source.inputStream().use(::readBounded)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in MIN_DIMENSION..MAX_DIMENSION && bounds.outHeight in MIN_DIMENSION..MAX_DIMENSION)
        val options = BitmapFactory.Options()
        while (
            bounds.outWidth / options.inSampleSize.coerceAtLeast(1) > MAX_DECODE_WIDTH ||
            bounds.outHeight / options.inSampleSize.coerceAtLeast(1) > MAX_DECODE_HEIGHT
        ) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("image_invalid")
        bitmap.recycle()
        val extension =
            when (bounds.outMimeType) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                else -> "png"
            }
        val name = "background-${UUID.randomUUID()}.$extension"
        val directory = File(filesDir, "display-backgrounds").apply { mkdirs() }
        val saved = AtomicFile(File(directory, name))
        val output = saved.startWrite()
        try {
            output.write(bytes)
            saved.finishWrite(output)
        } catch (error: Exception) {
            saved.failWrite(output)
            throw error
        }
        return name
    }

    /** 8192 字节逐次读取，累计超过 16MiB 立即失败，不放大内存。 */
    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size() + read <= MAX_BYTES.toInt())
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        /** 与旧实现一致：文件最大 16MiB。 */
        const val MAX_BYTES = 16L * 1024 * 1024

        /** 与旧实现一致：文件至少 1 字节。 */
        const val MIN_BYTES = 1L

        /** 与旧实现一致：解码边界 1..16384。 */
        const val MIN_DIMENSION = 1
        const val MAX_DIMENSION = 16384

        /** 与旧实现一致：解码目标上限 3840×2160。 */
        const val MAX_DECODE_WIDTH = 3840
        const val MAX_DECODE_HEIGHT = 2160

        /** 与旧实现一致：单次读取 buffer 8192 字节。 */
        const val READ_BUFFER_BYTES = 8192
    }
}
