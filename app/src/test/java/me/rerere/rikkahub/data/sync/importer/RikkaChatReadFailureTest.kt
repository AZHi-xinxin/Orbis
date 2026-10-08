package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RikkaChatReadFailureTest {
    @Test fun stagedDiagnosticsNeverExposeOriginalParserOrDatabaseText() {
        RikkaChatReadStage.entries.forEach { stage ->
            val failure = runCatching { readRikkaStage(stage) { error("private-source-and-credential") } }.exceptionOrNull()!!
            val public = ArchiveCapacity.publicError(failure)
            assertTrue(public.contains("RIKKA_${stage.name}"))
            assertFalse(public.contains("private-source"))
            assertNull(failure.cause)
        }
    }

    @Test fun capacityStorageAndCancellationRemainDistinguishable() {
        listOf(ArchiveReadException(ArchiveFailure.NODE_LIMIT), IOException("private-path"),
            CancellationException("cancel")).forEach { original ->
            val failure = runCatching { readRikkaStage(RikkaChatReadStage.MESSAGE) { throw original } }.exceptionOrNull()
            assertSame(original, failure)
        }
        assertEquals("ok", readRikkaStage(RikkaChatReadStage.MESSAGE) { "ok" })
    }
}
