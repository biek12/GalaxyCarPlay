package com.galaxy.autocluster.theme

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ThemeStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun rejects(action: () -> Unit) { try { action(); fail("应拒绝本次操作") } catch (_: Exception) {} }

    @Test fun savingDraftDoesNotReplaceAppliedSnapshotAndReopensIndependently() {
        val root = temporary.newFolder()
        val store = ThemeStore(root)
        val first = store.save(themeFixture(), null)
        store.apply(first.metadata.id, first.metadata.revision)
        val changed = ThemePackageCodec.withMetadata(first, first.metadata.copy(name = "修改后的草稿"))
        val second = store.save(changed, 1)
        assertEquals(2L, second.metadata.revision)
        assertEquals(1L, store.active()!!.metadata.revision)
        assertEquals("修改后的草稿", ThemeStore(root).load("custom")!!.metadata.name)
        assertEquals(first.metadata, ThemeStore(root).active()!!.metadata)
        store.apply("custom", 2)
        assertEquals(second.metadata, store.active()!!.metadata)
    }
    @Test fun staleEditorCannotOverwriteNewerSaveOrApplyWrongRevision() {
        val root = temporary.newFolder()
        val one = ThemeStore(root); val two = ThemeStore(root)
        val first = one.save(themeFixture(), null)
        two.save(first, 1)
        rejects { one.save(first, 1) }
        rejects { one.apply("custom", 1) }
        assertEquals(2L, one.load("custom")!!.metadata.revision)
        assertNull(one.active())
    }
    @Test fun builtinEditingRequiresCopyAndImportNeverOverwritesIt() {
        val builtin = themeFixture("builtin-classic")
        val store = ThemeStore(temporary.newFolder(), listOf(builtin))
        rejects { store.save(builtin, 1) }; rejects { store.delete(builtin.metadata.id, 1) }
        val copy = store.duplicate(builtin.metadata.id, "自己的仪表")
        val imported = store.import(ByteArrayInputStream(ThemePackageCodec.bytes(builtin)))
        assertNotEquals(builtin.metadata.id, copy.metadata.id)
        assertNotEquals(builtin.metadata.id, imported.metadata.id)
        assertEquals(3, store.library().entries.size)
        assertEquals(1, store.library().entries.count { it.builtIn })
    }
    @Test fun failedAtomicWriteKeepsPreviousSavedAndActivePackages() {
        val root = temporary.newFolder()
        val store = ThemeStore(root)
        val first = store.save(themeFixture(), null); store.apply("custom", 1)
        val failing = ThemeStore(root, writeAtomic = { file, bytes ->
            val output = file.startWrite()
            output.write(bytes, 0, bytes.size / 2)
            file.failWrite(output)
            throw IOException("injected_write_failure")
        })
        rejects { failing.save(first, 1) }; rejects { failing.apply("custom", 1) }
        assertEquals(first.metadata, store.load("custom")!!.metadata)
        assertEquals(first.metadata, store.active()!!.metadata)
    }
    @Test fun renameIsRevisionCheckedAndActiveThemeMustBeReplacedBeforeDelete() {
        val store = ThemeStore(temporary.newFolder())
        store.save(themeFixture(), null)
        val renamed = store.rename("custom", "新名称", 1)
        assertEquals(2L, renamed.metadata.revision)
        store.apply("custom", 2); rejects { store.delete("custom", 2) }
        val copy = store.duplicate("custom", "另一个主题")
        store.apply(copy.metadata.id, 1); store.delete("custom", 2)
        assertNull(store.load("custom")); assertEquals(copy.metadata, store.active()!!.metadata)
    }
    @Test fun failedImportKeepsLibraryAndCorruptEntriesAreReportedWithoutRemoval() {
        val root = temporary.newFolder(); val store = ThemeStore(root)
        store.save(themeFixture(), null)
        rejects { store.import(ByteArrayInputStream(byteArrayOf(2, 3))) }
        File(root, "broken.gctheme").writeBytes(byteArrayOf(4))
        val library = store.library()
        assertEquals(1, library.entries.size); assertEquals(listOf("broken"), library.unreadableIds)
        assertTrue(File(root, "broken.gctheme").exists())
    }
}
