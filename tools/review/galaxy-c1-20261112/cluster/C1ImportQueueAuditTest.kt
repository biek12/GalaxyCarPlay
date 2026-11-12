package com.galaxy.autocluster

import android.graphics.Bitmap
import android.os.Looper
import com.galaxy.autocluster.ui.BackgroundImageImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** C1既定容量1合同：两次正常图片选择不能各排入一个待执行drain。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class C1ImportQueueAuditTest {
    /** 调度器暂缓执行，用于观测真实导入器向worker提交的任务数；不复制导入逻辑。 */
    private class PausedExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        /** 恢复调度，所有文件验证/解码/AtomicFile逻辑仍由生产代码执行。 */
        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test
    fun twoSelectionsKeepOnlyOneScheduledWorkerAndSaveLatestOriginalBytes() {
        val app = RuntimeEnvironment.getApplication()
        val sourceDir = File(app.cacheDir, "c1-audit-input").apply { mkdirs() }
        val first = png(File(sourceDir, "first.png"), 0xFF00FF00.toInt())
        val latest = png(File(sourceDir, "latest.png"), 0xFFFF0000.toInt())
        val executor = PausedExecutor()
        val results = mutableListOf<Result<String>>()
        val importer = BackgroundImageImport(app, executor, onResult = results::add)
        try {
            importer.importImage(first)
            importer.importImage(latest)
            val queuedBeforeExecution = executor.tasks.size
            executor.drain()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("仅最新选择返回结果", 1, results.size)
            val saved = File(app.filesDir, "display-backgrounds/${results.single().getOrThrow()}")
            assertTrue("实际保存最新图片原始字节", saved.readBytes().contentEquals(latest.readBytes()))
            assertEquals("容量1合同也约束真实worker排队任务，不能为每次选择再排一个drain", 1, queuedBeforeExecution)
        } finally {
            importer.close()
        }
    }

    /** 生成两份不同的真实PNG输入，输出验证比较实际原始文件内容。 */
    private fun png(target: File, color: Int): File {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        return target
    }
}
