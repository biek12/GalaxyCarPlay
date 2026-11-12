package com.galaxy.autocluster.theme

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ThemePackageCodecTest {
    private fun rejects(action: () -> Unit) { try { action(); fail("应拒绝无效主题包") } catch (_: Exception) {} }
    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() } }
    }.toByteArray()

    @Test fun xmlAndResourcesRoundTripWithChineseAndEscapedName() {
        val original = themeFixture()
        val restored = ThemePackageCodec.read(ByteArrayInputStream(ThemePackageCodec.bytes(original)))
        assertEquals(original.metadata, restored.metadata)
        assertEquals(original.names(), restored.names())
        original.names().forEach { assertArrayEquals(original.content(it), restored.content(it)) }
    }
    @Test fun packageDefensivelyCopiesResourceArrays() {
        val data = byteArrayOf(8)
        val theme = ThemePackageCodec.create(ThemeMetadata("copy", "副本", 1), "<scene/>".toByteArray(), "<styles/>".toByteArray(), mapOf("assets/a.png" to data))
        data[0] = 3; theme.content("assets/a.png")[0] = 4
        assertEquals(8.toByte(), theme.content("assets/a.png")[0])
    }
    @Test fun externalDeclarationsAndMalformedXmlAreRejected() {
        for (scene in listOf("<!DOCTYPE scene [<!ENTITY e SYSTEM 'file:///should-not-read'>]><scene>&e;</scene>", "<scene><node></scene>"))
            rejects { ThemePackageCodec.create(ThemeMetadata("bad", "错误包", 1), scene.toByteArray(), "<styles/>".toByteArray()) }
    }
    @Test fun unsupportedVersionsAndMissingMandatoryFilesAreRejected() {
        val files = themeFixture().copyEntries()
        rejects { ThemePackageCodec.read(ByteArrayInputStream(zip(files - "styles.xml"))) }
        val future = files + ("manifest.xml" to files.getValue("manifest.xml").toString(Charsets.UTF_8).replace("schemaVersion=\"1\"", "schemaVersion=\"2\"").toByteArray())
        rejects { ThemePackageCodec.read(ByteArrayInputStream(zip(future))) }
    }
    @Test fun traversalAbsoluteNamesAndScriptsNeverBecomePackageResources() {
        for (name in listOf("../escape.png", "/tmp/a.png", "assets/../a.png", "assets\\a.png", "assets/a.js", "assets//a.png")) {
            rejects { ThemePackageCodec.read(ByteArrayInputStream(zip(themeFixture().copyEntries() + (name to byteArrayOf(1))))) }
        }
    }
    @Test fun streamingCompressionCannotBypassUncompressedResourceBudget() {
        val data = zip(themeFixture().copyEntries() + ("assets/large.png" to ByteArray(ThemePackageCodec.MAX_FILE_BYTES + 1)))
        assertTrue(data.size < 100_000)
        rejects { ThemePackageCodec.read(ByteArrayInputStream(data)) }
    }
    @Test fun directoryEntriesCannotHideCompressedPayload() {
        rejects { ThemePackageCodec.read(ByteArrayInputStream(zip(themeFixture().copyEntries() + ("assets/" to byteArrayOf(1))))) }
    }
    @Test fun callerOwnsInputAndOutputLifetimes() {
        var inputClosed = false
        val input = object : ByteArrayInputStream(ThemePackageCodec.bytes(themeFixture())) { override fun close() { inputClosed = true } }
        ThemePackageCodec.read(input); assertFalse(inputClosed)
        var outputClosed = false
        val output = object : ByteArrayOutputStream() { override fun close() { outputClosed = true } }
        ThemePackageCodec.write(themeFixture(), output); assertFalse(outputClosed)
        assertTrue(output.size() > 0)
    }
}
