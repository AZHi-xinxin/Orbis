package me.rerere.rikkahub.data.ai.checkpoint

import org.junit.Assert.assertEquals
import org.junit.Test

class CheckpointDiagnosticsTest {
    @Test fun fixedCodeIsAvailableWithoutPrivateText() {
        assertEquals("database_tail_changed", checkpointDiagnostic(GenerationCheckpointException("database_tail_changed")))
    }
    @Test fun arbitraryExceptionMessagesAndCodesNeverEscape() {
        assertEquals("unclassified", checkpointDiagnostic(IllegalStateException("private secret")))
        assertEquals("unclassified", checkpointDiagnostic(GenerationCheckpointException("private secret")))
    }
}
