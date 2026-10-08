package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class RikkaWholeImportAccountingTest {
    @Test fun cancellationBeforeAnyCommitHasZeroConfirmedCounts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        try {
            accountRikkaWholeImport(dispatcher) { throw CancellationException("synthetic") }
            fail("must propagate cancellation")
        } catch (cancelled: RikkaChatImportCancelledException) {
            assertEquals(RikkaChatImportResult(), cancelled.partialResult)
        }
    }

    @Test fun promptCancellationAfterFinalCommitRetainsAllCounts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler, "synthetic-import-io")
        lateinit var job: Job
        var caught: RikkaChatImportCancelledException? = null
        var blockReturned = false
        job = launch {
            try {
                accountRikkaWholeImport(dispatcher) { accounting ->
                    accounting.skipped()
                    accounting.imported(2, 3)
                    // No further suspension/check in the worker: withContext's return dispatch
                    // is the cancellation point that the previous phone implementation missed.
                    job.cancel()
                    blockReturned = true
                }
                fail("must not return success to the cancelled caller")
            } catch (cancelled: RikkaChatImportCancelledException) { caught = cancelled }
        }
        advanceUntilIdle()
        assertTrue(blockReturned)
        assertEquals(RikkaChatImportResult(1, 1, 2, 3), caught?.partialResult)
        assertTrue(job.isCancelled)
    }

    @Test fun cancellationDuringAtomicCommitAccountsThatCommitButStopsTheNextWindow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        lateinit var job: Job
        var caught: RikkaChatImportCancelledException? = null
        var secondPrepared = false
        job = launch {
            try {
                accountRikkaWholeImport(dispatcher) { accounting ->
                    withContext(NonCancellable) {
                        job.cancel()
                        accounting.imported(1, 0)
                    }
                    currentCoroutineContext().ensureActive()
                    secondPrepared = true
                }
            } catch (cancelled: RikkaChatImportCancelledException) { caught = cancelled }
        }
        advanceUntilIdle()
        assertFalse(secondPrepared)
        assertEquals(RikkaChatImportResult(1, 0, 1, 0), caught?.partialResult)
    }

    @Test fun failedReceiptCallbackCannotEraseAlreadyCommittedCounts() = runTest {
        try {
            accountRikkaWholeImport(StandardTestDispatcher(testScheduler), onProgress = {
                throw java.io.IOException("synthetic private filename must not be exposed")
            }) { it.imported(4, 2) }
            fail()
        } catch (failure: RikkaPartialImportException) {
            assertEquals(RikkaChatImportResult(1, 0, 4, 2), failure.partialResult)
            assertFalse(failure.message!!.contains("private filename"))
        }
    }

    @Test fun successfulAndSkippedWindowsReportOnlyTheirOwnConfirmedAttachments() = runTest {
        val progress = mutableListOf<RikkaChatImportResult>()
        val result = accountRikkaWholeImport(StandardTestDispatcher(testScheduler), progress::add) {
            it.imported(2, 1)
            it.skipped()
            it.imported(3, 0)
        }
        assertEquals(RikkaChatImportResult(2, 1, 5, 1), result)
        assertEquals(listOf(RikkaChatImportResult(1, 0, 2, 1), RikkaChatImportResult(1, 1, 2, 1), result), progress)
    }
}
