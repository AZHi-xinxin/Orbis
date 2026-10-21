package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import org.junit.Assert.*
import org.junit.Test

class BackupRecoveryConnectionTest {
    private val connection = BackupRecoveryConnection(target = "webdav",
        webdav = WebDavConfig(url = "https://example.invalid/archive", username = "synthetic", password = "test-only"))
    @Test fun connectionRoundTripsButDoesNotIncludeChatsOrEnableUpload() {
        val decoded = BackupRecoveryConnection.decode(connection.encode())
        assertEquals(connection, decoded)
        val before = Settings.dummy()
        val restored = decoded.applyTo(before.copy(autoBackup = before.autoBackup.copy(enabled = true)))
        assertEquals(before.assistants, restored.assistants)
        assertEquals(before.providers, restored.providers)
        assertEquals(connection.webdav, restored.webDavConfig)
        assertFalse(restored.autoBackup.enabled)
        assertFalse(decoded.displayDestination().contains("test-only"))
        assertFalse(decoded.displayDestination().contains("synthetic"))
    }
    @Test fun unsupportedOrAmbiguousSourcesAreRejected() {
        for (bad in listOf(connection.copy(version = 2), connection.copy(format = "other"),
            connection.copy(s3 = S3Config()), connection.copy(target = "unknown"),
            connection.copy(webdav = connection.webdav!!.copy(url = "https://user:pass@example.invalid")),
            connection.copy(webdav = connection.webdav!!.copy(url = "file:///private")),
            connection.copy(webdav = connection.webdav!!.copy(path = "../other")))) {
            assertThrows(IllegalArgumentException::class.java) { bad.validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { BackupRecoveryConnection.decode(ByteArray(70_000)) }
        assertFalse(BackupRecoveryConnection.ready(Settings.dummy()))
    }
    @Test fun onlyNewestCompleteVersionsAreSelected() {
        val first = BackupRestorePoint(id = "00000000-0000-0000-0000-000000000001",
            createdAt = 1, bytes = 10, sha256 = "0".repeat(64))
        val newest = first.copy(id = "00000000-0000-0000-0000-000000000002", createdAt = 2)
        val partial = first.copy(createdAt = 3, includesFiles = false)
        assertEquals(newest, newestCompleteBackup(listOf(partial, first, newest)))
        assertThrows(IllegalArgumentException::class.java) { newestCompleteBackup(listOf(partial)) }
    }

    @Test fun latestOverwriteBackupIsSelectedWithoutChangingConnectionDocumentVersion() {
        val legacy = BackupRestorePoint(id = "00000000-0000-0000-0000-000000000001",
            createdAt = 1, bytes = 10, sha256 = "0".repeat(64))
        val latest = legacy.copy(version = 2, id = "00000000-0000-0000-0000-000000000002", createdAt = 2)
        val partial = latest.copy(createdAt = 3, includesFiles = false)
        assertEquals(latest, newestCompleteBackup(listOf(partial, legacy, latest)))
        assertEquals(1, BackupRecoveryConnection.decode(connection.encode()).version)
    }

    @Test fun recoverySelectionPreservesLegacyFallbackAndRejectsUnknownNewestFormat() {
        val legacy = BackupRestorePoint(id = "00000000-0000-0000-0000-000000000001",
            createdAt = 1, bytes = 10, sha256 = "0".repeat(64))
        assertEquals(legacy, newestCompleteBackup(listOf(legacy)))
        assertThrows(IllegalArgumentException::class.java) {
            newestCompleteBackup(listOf(legacy, legacy.copy(version = 3, createdAt = 2)))
        }
    }
}
