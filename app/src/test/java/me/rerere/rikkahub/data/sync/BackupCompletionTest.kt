package me.rerere.rikkahub.data.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import me.rerere.rikkahub.data.datastore.BackupReminderConfig
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class BackupCompletionTest {
    @Test fun legacyGlobalTimeDoesNotProveAnyDestination() {
        val config = BackupReminderConfig(lastBackupTime = 123)
        assertEquals(0L, config.completedAt("webdav-one"))
        assertEquals(0L, config.completedAt("s3-one"))
    }
    @Test fun localExportAndDifferentServersHaveSeparateReceipts() {
        val settings = Settings.dummy()
        val first = ContinuousBackup.key("webdav", settings)
        val second = ContinuousBackup.key("webdav", settings.copy(webDavConfig =
            settings.webDavConfig.copy(url = "https://example.invalid/other")))
        val s3 = ContinuousBackup.key("s3", settings)
        val config = BackupReminderConfig().completed(first, 10).completed("local-export", 20).completed(s3, 30)
        assertEquals(10L, config.completedAt(first))
        assertEquals(0L, config.completedAt(second))
        assertEquals(30L, config.completedAt(s3))
        assertEquals(30L, config.lastBackupTime)
        assertEquals(30L, config.completed(s3, 5).completedAt(s3))
    }
    @Test fun exportClosesDestinationBeforeReadbackAndComparesExactBytes() {
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        val bytes = ByteArray(140_000) { it.toByte() }
        VerifiedBackupExport.copy({ bytes.inputStream() }, { output }, {
            assertTrue(closed); output.toByteArray().inputStream()
        })
        assertArrayEquals(bytes, output.toByteArray())
    }
    @Test fun failedOrTruncatedExportsNeverGetACompletionReceipt() {
        for (kind in listOf("write", "read", "short", "changed")) {
            var config = BackupReminderConfig()
            try {
                VerifiedBackupExport.copy({ "full archive".byteInputStream() }, {
                    if (kind == "write") throw IOException("storage unavailable")
                    ByteArrayOutputStream()
                }, {
                    when (kind) {
                        "read" -> throw IOException("read permission lost")
                        "short" -> "full".byteInputStream()
                        else -> "FULL archive".byteInputStream()
                    }
                })
                config = config.completed("local-export", 42)
                fail("Expected failure for $kind")
            } catch (_: IOException) {
            } catch (_: IllegalArgumentException) { }
            assertEquals(0L, config.lastBackupTime)
            assertEquals(0L, config.completedAt("local-export"))
        }
    }
}
