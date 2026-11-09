package com.galaxy.autorecord.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

// Robolectric 只为让 android.util.Log 可用；本类不依赖任何设备状态。
@RunWith(RobolectricTestRunner::class)
class AtomicFilesTest {
    private fun newDir(): File = Files.createTempDirectory("autorecord").toFile()

    @Test
    fun `原子写入成功后内容完整且无临时文件残留`() {
        val dir = newDir()
        val target = File(dir, "segment.json")
        assertTrue(AtomicFiles.writeAtomically(target, "{\"a\":1}".toByteArray()))
        assertEquals("{\"a\":1}", target.readText())
        assertFalse(File(dir, "segment.json.tmp").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `改名失败时保留既有内容并清掉临时文件`() {
        val dir = newDir()
        val target = File(dir, "segment.json")
        // 目标是非空目录：既不能被覆盖也不能被删除，足以模拟改名失败。
        target.mkdirs()
        File(target, "keep").writeText("x")
        assertFalse(AtomicFiles.writeAtomically(target, "new".toByteArray()))
        assertTrue(File(target, "keep").exists())
        assertFalse(File(dir, "segment.json.tmp").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `收尾把writing改名为最终名`() {
        val dir = newDir()
        val video = File(dir, "front.mp4")
        val writing = SegmentLayout.writingFile(video)
        writing.writeText("mp4")
        assertTrue(AtomicFiles.commitVideo(video))
        assertTrue(video.exists())
        assertFalse(writing.exists())
        dir.deleteRecursively()
    }

    @Test
    fun `段目录名包含日期与段号`() {
        val base = newDir()
        val dir = SegmentLayout.segmentDir(base, 1_700_000_000_000L, 7)
        assertTrue(dir.name.endsWith("-007"))
        assertTrue(dir.parentFile.name.length == 8)
        assertEquals(File(dir, "front.mp4"), SegmentLayout.videoFile(dir, "front"))
        base.deleteRecursively()
    }

    @Test fun `空视频不能收尾成完整片段且原子覆盖保留完整清单`() {
        val dir = newDir()
        val video = File(dir,"front.mp4")
        SegmentLayout.writingFile(video).writeBytes(byteArrayOf())
        assertFalse(AtomicFiles.commitVideo(video))
        assertFalse(video.exists())
        val manifest=File(dir,"segment.json")
        assertTrue(AtomicFiles.writeAtomically(manifest,"old".toByteArray()))
        assertTrue(AtomicFiles.writeAtomically(manifest,"new".toByteArray()))
        assertEquals("new",manifest.readText())
        assertFalse(dir.listFiles()!!.any {it.name.endsWith(".tmp")})
        dir.deleteRecursively()
    }
}
