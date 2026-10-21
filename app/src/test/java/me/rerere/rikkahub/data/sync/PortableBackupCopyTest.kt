package me.rerere.rikkahub.data.sync

import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortableBackupCopyTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun portableCopyScrubsCredentialsButKeepsOriginalAndIdenticalAttachments() = runBlocking {
        val source = temporary.newFile()
        val settings = """{"providers":[{"apiKey":"synthetic-secret"}],"prompt":"study"}"""
        val content = ByteArray(10000) { (it % 250).toByte() }
        ZipOutputStream(source.outputStream()).use {
            it.putNextEntry(ZipEntry("settings.json")); it.write(settings.toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry("upload/test.bin")); it.write(content); it.closeEntry()
        }
        val copy = portableBackupCopy(source, temporary.root)
        ZipFile(copy).use {
            assertFalse(it.getInputStream(it.getEntry("settings.json")).reader().readText().contains("synthetic-secret"))
            assertArrayEquals(content, it.getInputStream(it.getEntry("upload/test.bin")).readBytes())
        }
        ZipFile(source).use { assertEquals(settings, it.getInputStream(it.getEntry("settings.json")).reader().readText()) }
    }
}
