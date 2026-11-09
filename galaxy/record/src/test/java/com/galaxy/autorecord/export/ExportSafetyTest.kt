package com.galaxy.autorecord.export

import android.media.MediaFormat
import android.net.Uri
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
class ExportSafetyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `首帧未到时保持空白且两路偏移各自保留`() {
        val a = TimedFrames<String> {}
        val b = TimedFrames<String> {}
        a.offer(100_000, "front", 0)
        b.offer(180_000, "rear", 0)
        assertNull(a.current)
        assertNull(b.current)
        a.advance(100_000)
        b.advance(100_000)
        assertEquals("front", a.current)
        assertNull(b.current)
        b.advance(180_000)
        assertEquals("rear", b.current)
    }

    @Test fun `未来帧未到保持旧帧到达后释放旧帧关闭幂等`() {
        val disposed = mutableListOf<String>()
        val frames = TimedFrames<String> { disposed += it }
        frames.offer(0, "first", 0)
        frames.offer(40_000, "next", 0)
        frames.advance(39_999)
        assertEquals("first", frames.current)
        assertTrue(disposed.isEmpty())
        frames.advance(40_000)
        assertEquals("next", frames.current)
        assertEquals(listOf("first"), disposed)
        frames.offer(80_000, "future", 40_000)
        frames.close()
        frames.close()
        assertEquals(listOf("first", "next", "future"), disposed)
    }

    private fun task(dirs: List<File>, direction: String = "front") = ExportTask(
        RuntimeEnvironment.getApplication(),
        ExportTask.ExportRequest(dirs, false, direction, false, Uri.parse("content://unavailable/export")),
    )

    @Test fun `源段消失不能静默从导出列表剔除`() {
        val result = task(listOf(temp.newFolder("exists"), File(temp.root, "gone"))).execute()
        assertFalse(result.success)
        assertTrue(result.message.contains("源片段已不可用"))
    }

    @Test fun `损坏清单拒绝导出并保留源文件`() {
        val dir = temp.newFolder()
        val source = File(dir, "front.mp4").apply { writeText("evidence") }
        val result = task(listOf(dir)).execute()
        assertFalse(result.success)
        assertTrue(result.message.contains("清单"))
        assertEquals("evidence", source.readText())
    }

    @Test fun `无效方向及取消返回失败`() {
        assertFalse(task(listOf(temp.newFolder()), "bad").execute().success)
        val task = task(listOf(temp.newFolder()))
        task.cancel()
        assertTrue(task.execute().message.contains("已取消"))
    }

    @Test fun `同分辨率但SPS不同拒绝无损拼接且不修改缓冲位置`() {
        fun format(data: ByteArray) = MediaFormat.createVideoFormat("video/avc", 1280, 800).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(data))
        }
        val task = task(emptyList())
        val first = format(byteArrayOf(1, 2, 3))
        val same = format(byteArrayOf(1, 2, 3))
        first.getByteBuffer("csd-0")!!.position(1)
        assertTrue(task.sameCodec(first, same))
        assertEquals(1, first.getByteBuffer("csd-0")!!.position())
        assertFalse(task.sameCodec(first, format(byteArrayOf(1, 2, 4))))
        assertFalse(task.sameCodec(first, MediaFormat.createVideoFormat("video/avc", 1280, 800)))
    }
}
