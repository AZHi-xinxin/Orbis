package me.rerere.rikkahub.data.sync.importer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RikkaChatArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun zip(vararg names: String): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { out -> names.forEach { name ->
            out.putNextEntry(ZipEntry(name)); out.write("fixture".toByteArray()); out.closeEntry()
        } }
    }
    @Test fun extractsOnlyChatAllowlist() {
        val target = temp.newFolder()
        RikkaChatArchive.extract(zip("rikka_hub.db", "upload/pic.jpg", "settings.json", "skills/private.txt", "files/lc_private"), target)
        assertEquals(setOf("rikka_hub.db", "upload/pic.jpg"), target.walkTopDown().filter { it.isFile }.map { it.relativeTo(target).invariantSeparatorsPath }.toSet())
    }
    @Test fun mapsLegacyWalWithoutCopyingShm() {
        val target = temp.newFolder()
        RikkaChatArchive.extract(zip("rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm"), target)
        assertTrue(File(target, "rikka_hub.db-wal").isFile)
        assertFalse(File(target, "rikka_hub-shm").exists())
    }
    @Test fun rejectsTraversalAndAbsolutePaths() {
        listOf("../escape", "upload/../escape", "/absolute", "C:/escape", "upload\\escape").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { RikkaChatArchive.extract(zip("rikka_hub.db", path), temp.newFolder()) }
        }
    }
    @Test fun rejectsAmbiguousWalAndMissingDb() {
        assertThrows(IllegalArgumentException::class.java) { RikkaChatArchive.extract(zip("rikka_hub.db", "rikka_hub-wal", "rikka_hub.db-wal"), temp.newFolder()) }
        assertThrows(IllegalArgumentException::class.java) { RikkaChatArchive.extract(zip("settings.json"), temp.newFolder()) }
    }
    @Test fun copiesWithEnforcedActualByteCap() {
        assertThrows(IllegalArgumentException::class.java) { RikkaChatArchive.copyLimited(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream(), 99) }
        assertEquals(100, RikkaChatArchive.copyLimited(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream(), 100).toInt())
    }
    @Test fun onlyRemapsFilesFromUpload() {
        assertEquals("a.png", RikkaChatArchive.uploadName("file:///data/user/0/me.rerere.rikkahub/files/upload/a.png"))
        listOf("file:///etc/passwd", "file:///private/token", "file://evil/upload/a.png", "file:///upload/%2e%2e", "content://media/upload/a.png").forEach { assertNull(RikkaChatArchive.uploadName(it)) }
    }
    @Test fun stableImportIdsAvoidOverwriteAndDistinguishTypes() {
        assertEquals(rikkaImportId("conversation", "a"), rikkaImportId("conversation", "a"))
        assertNotEquals(rikkaImportId("conversation", "a"), rikkaImportId("node", "a"))
        assertNotEquals(rikkaImportId("conversation", "a"), rikkaImportId("conversation", "b"))
    }
}
