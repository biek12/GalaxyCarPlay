package com.galaxy.autorecord.storage

import com.galaxy.autorecord.core.SegmentManifest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/** Lead反例：检查真实证据保护和迟到事件，不修改WorkBuddy的冻结候选。 */
@RunWith(RobolectricTestRunner::class)
class SegmentTransactionBoundaryTest {
    private fun tx(root: File, store: SegmentStorePort) = SegmentTransaction(
        root, null, 1_700_000_000_000L, 10_000_000_000L, 0, listOf("front", "rear", "left", "right"), "test", store)
    private fun receipt(tx: SegmentTransaction) = ChannelWriteResult(
        tx.transactionId, "front", "video/avc", "fixture.encoder", "software", 1280, 800, 25, 2_000_000, 1, 100, 0, 3_960_000)

    @Test fun `建段竞争失败后的abort不能覆盖原件`() {
        val root=Files.createTempDirectory("record-old-evidence").toFile()
        try {
            val transaction=tx(root,RealSegmentStorePort())
            val dir=transaction.segmentDirectory().apply {mkdirs()}
            val old=File(dir,"segment.json").apply {writeText("ORIGINAL_EVIDENCE")}
            assertTrue(runCatching {transaction.begin()}.isFailure)
            runCatching {transaction.abort()}
            transaction.close()
            assertEquals("ORIGINAL_EVIDENCE",old.readText())
        } finally {root.deleteRecursively()}
    }
    @Test fun `旧事务事件不能毒化当前方向`() {
        val store=Store();val t=tx(File("/tmp/record-counterexample"),store);t.begin()
        assertFalse(t.commitChannel(receipt(t).copy(transactionId="old-session")).succeeded)
        assertNull(t.channelOutcome("front"))
        assertTrue(t.commitChannel(receipt(t)).succeeded)
        assertEquals(1,store.commits)
    }
    @Test fun `成功提交后仍拒绝异事务与冲突重复`() {
        val store=Store();val t=tx(File("/tmp/record-counterexample"),store);t.begin()
        val first=t.commitChannel(receipt(t));assertTrue(first.succeeded)
        assertFalse(t.commitChannel(receipt(t).copy(transactionId="old-session")).succeeded)
        assertFalse(t.commitChannel(receipt(t).copy(frameCount=99)).succeeded)
        assertSame(first,t.channelOutcome("front"))
        assertEquals(1,store.commits)
    }
    @Test fun `构造后父目录换为外部链接也不能写出根目录`() {
        val base=Files.createTempDirectory("record-path-swap").toFile()
        try {
            val root=File(base,"root").apply {mkdir()};val outside=File(base,"outside").apply {mkdir()}
            val t=tx(root,RealSegmentStorePort());val parent=t.segmentDirectory().parentFile!!
            parent.parentFile!!.mkdirs()
            Files.createSymbolicLink(parent.toPath(),outside.toPath())
            assertTrue(runCatching {t.begin()}.isFailure)
            assertEquals(0,outside.listFiles()!!.size)
        } finally {base.deleteRecursively()}
    }
    @Test fun `段相对PTS保留起始缺口而非减去各路首帧`() {
        val store=Store();val t=tx(File("/tmp/record-counterexample"),store);t.begin()
        t.commitChannel(receipt(t).copy(firstPtsUs=500_000,lastPtsUs=4_460_000))
        t.finish()
        assertEquals(4460L,store.manifests.last().durationMillis)
    }
    private class Store : SegmentStorePort {
        val manifests=mutableListOf<SegmentManifest>();var commits=0
        override fun segmentDirExists(dir: File)=false
        override fun ensureSegmentDir(dir: File)=true
        override fun writeManifest(segmentDir: File,manifest: SegmentManifest): Boolean {manifests+=manifest;return true}
        override fun commitVideo(video: File): Boolean {commits++;return true}
        override fun fileLength(file: File)=4096L
        override fun fileExists(file: File)=true
    }
}
