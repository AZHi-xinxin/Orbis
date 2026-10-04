package me.rerere.rikkahub.data.recovery

import org.junit.Assert.*
import org.junit.Test

class EmergencyRecoveryErrorsTest {
    @Test fun exceptionPayloadsAreNotShown() {
        val private = "synthetic-private-content-do-not-display"
        for (message in listOf(private, "checksum $private", "emergency_restore_package_mismatch $private", "ENOSPC $private")) {
            assertFalse(emergencyRecoveryError(IllegalArgumentException(message)).contains(private))
        }
    }
    @Test fun versionAndStorageMismatchRemainActionable() {
        assertTrue(emergencyRecoveryError(Exception("emergency_restore_newer_version")).contains("更新版本"))
        assertTrue(emergencyRecoveryError(Exception("emergency_restore_storage_path_mismatch")).contains("迁移附件"))
    }
    @Test fun cancellationIsNotReportedAsSuccessfulBackupOrAsRawPrivateError() {
        val message = emergencyRecoveryError(java.util.concurrent.CancellationException("synthetic-private-cancellation-detail"))
        assertTrue(message.contains("取消或中断"))
        assertTrue(message.contains("尚未确认"))
        assertFalse(message.contains("synthetic-private-cancellation-detail"))
    }

    @Test fun unsafeSourceNameIsActionableWithoutExposingItsName() {
        val error = java.io.IOException("Unsafe archive path: files/private-secret:arm64")
        val message = emergencyRecoveryError(error)
        assertTrue(message.contains("文件名"))
        assertFalse(message.contains("private-secret"))
        assertEquals("ORBIS-RESCUE/E_PATH_POLICY stage=scan", emergencyRecoveryDiagnostic(error, "scan"))
    }

    @Test fun nestedKnownErrorKeepsOnlyFixedCategoryAndWhitelistedStage() {
        val error = Exception("private outer key", java.io.IOException("emergency_mount_restore_required private path"))
        assertEquals("ORBIS-RESCUE/E_MOUNT_RESTORE stage=unknown",
            emergencyRecoveryDiagnostic(error, "copy private path and key"))
        assertTrue(emergencyRecoveryError(error).contains("权限尚未全部恢复"))
    }

    @Test fun diagnosticDoesNotPrintUnknownTypesPayloadsOrCyclicCauses() {
        class SyntheticSecretException : RuntimeException("secret-token-and-chat")
        val a = SyntheticSecretException()
        val b = Exception("other private message")
        a.initCause(b); b.initCause(a)
        assertEquals("ORBIS-RESCUE/E_UNCLASSIFIED stage=verify", emergencyRecoveryDiagnostic(a, "verify"))
        assertFalse(emergencyRecoveryError(a).contains("secret-token-and-chat"))
    }

    @Test fun integrityTakesPrecedenceOverGenericChangedInDiagnostic() {
        assertEquals("ORBIS-RESCUE/E_INTEGRITY stage=readback",
            emergencyRecoveryDiagnostic(Exception("checksum mismatch changed private-name"), "readback"))
    }

    @Test fun spacePermissionCancellationAndFormatCategoriesRemainBounded() {
        val errors = listOf(
            java.io.IOException("ENOSPC confidential") to "E_STORAGE_FULL",
            java.nio.file.AccessDeniedException("private-name") to "E_ACCESS_DENIED",
            java.io.InterruptedIOException("private reason") to "E_INTERRUPTED",
            java.util.zip.ZipException("private malformed archive") to "E_INTEGRITY",
        )
        errors.forEach { (error, code) ->
            assertEquals("ORBIS-RESCUE/$code stage=save", emergencyRecoveryDiagnostic(error, "save"))
        }
    }

    @Test fun workspacePathsAreNotMisreportedAsFullDisk() {
        val error = java.nio.file.AccessDeniedException("/private/workspaces/file")
        val message = emergencyRecoveryError(error)
        assertFalse(message.contains("空间不足"))
        assertTrue(message.contains("无法读取"))
        assertEquals("ORBIS-RESCUE/E_ACCESS_DENIED stage=scan", emergencyRecoveryDiagnostic(error, "scan"))
    }
}
