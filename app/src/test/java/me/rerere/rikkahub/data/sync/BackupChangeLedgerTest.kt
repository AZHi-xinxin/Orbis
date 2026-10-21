package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BackupChangeLedgerTest {
    @Test fun firstBindingIsPendingWithoutManualBackup() {
        assertTrue(BackupChangeLedger().pending("folder-a"))
    }
    @Test fun writesDuringUploadRemainPending() {
        val captured = BackupChangeLedger().revision
        val updated = BackupChangeLedger().changed().completed("a", captured, 100)
        assertTrue(updated.pending("a"))
        assertEquals(1L, updated.targets.getValue("a").revision)
    }
    @Test fun destinationsAcknowledgeIndependently() {
        val state = BackupChangeLedger().completed("local", 1, 100)
        assertFalse(state.pending("local"))
        assertTrue(state.pending("folder"))
        assertTrue(state.pending("server"))
    }
    @Test fun failureKeepsPreviousVersionAndPending() {
        val state = BackupChangeLedger().completed("server", 1, 100).changed().failed("server", "offline")
        assertEquals(100L, state.targets.getValue("server").completedAt)
        assertTrue(state.pending("server"))
    }
    @Test fun processRestartRetainsPendingAndReceipt() {
        val state = BackupChangeLedger().completed("folder", 1, 100).changed()
        assertEquals(state, Json.decodeFromString<BackupChangeLedger>(Json.encodeToString(state)))
    }
    @Test fun changedTargetDoesNotInheritSuccess() {
        val state = BackupChangeLedger().completed("server-a", 1, 100)
        assertTrue(state.pending("server-b"))
        assertNull(state.targets["server-b"])
    }
    @Test fun existingArchiveReviewDoesNotAcknowledgeUnwrittenData() {
        val state = BackupChangeLedger().failed("folder", "old archive", true)
        assertTrue(state.targets.getValue("folder").needsReview)
        val allowed = state.allowExisting("folder")
        assertFalse(allowed.targets.getValue("folder").needsReview)
        assertTrue(allowed.pending("folder"))
    }
    @Test fun oldCompletionCannotLoseNewerRevision() {
        val state = BackupChangeLedger().changed().completed("a", 2, 200).completed("a", 1, 300)
        assertEquals(2L, state.targets.getValue("a").revision)
    }
}
