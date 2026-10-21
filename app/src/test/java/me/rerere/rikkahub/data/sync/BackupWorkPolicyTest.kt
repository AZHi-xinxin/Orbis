package me.rerere.rikkahub.data.sync

import org.junit.Assert.*
import org.junit.Test

class BackupWorkPolicyTest {
    @Test fun explicitSaveIsIndependentOfOldRetryQueue() {
        assertNotEquals("orbis-backup-now", BackupWorkPolicy.AUTOMATIC)
        assertNotEquals(BackupWorkPolicy.AUTOMATIC, BackupWorkPolicy.REQUESTED)
        assertFalse(BackupWorkPolicy.retry(manual = true, completed = false))
        assertFalse(BackupWorkPolicy.retry(manual = true, completed = true))
        assertTrue(BackupWorkPolicy.retry(manual = false, completed = false))
        assertFalse(BackupWorkPolicy.retry(manual = false, completed = true))
    }
    @Test fun failuresNeverDiscloseExceptionText() {
        val secret = "https://private.example/key?password=private-token"
        assertEquals("validation_failed", backupFailureCode(IllegalArgumentException(secret)))
        assertEquals("io_failed", backupFailureCode(java.io.IOException(secret)))
        assertEquals("network_timeout", backupFailureCode(RuntimeException(secret, java.net.SocketTimeoutException(secret))))
        assertEquals("dns_unavailable", backupFailureCode(java.net.UnknownHostException(secret)))
        assertEquals("not_completed", backupFailureCode(RuntimeException(secret)))
    }
}
