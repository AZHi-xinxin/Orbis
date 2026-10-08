package me.rerere.rikkahub.ui.pages.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.sync.importer.RikkaChatImportCancelledException
import me.rerere.rikkahub.data.sync.importer.RikkaChatImportResult
import me.rerere.rikkahub.data.sync.importer.RikkaPartialImportException
import org.junit.Assert.*
import org.junit.Test

class RikkaPhoneImportReceiptTest {
    private class Storage {
        var raw: String? = null
        var failWrites = false
        var writes = 0
        var nextId = 0
        fun owner(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = RikkaPhoneImportReceiptOwner(
            read = { raw }, write = { if (failWrites) error("synthetic storage error"); raw = it; writes++ },
            dispatcher = dispatcher, now = { 1000 },
            newId = { "00000000-0000-4000-8000-${(++nextId).toString().padStart(12, '0')}" })
    }

    @Test fun leavingPageCancellationKeepsCountsAndRecreatedOwnerReadsThem() = runTest {
        val storage = Storage(); val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = storage.owner(dispatcher)
        var propagated = false
        val page = launch {
            try {
                owner.import { progress ->
                    progress(RikkaChatImportResult(2, 1, 4, 3))
                    CompletableDeferred<Unit>().await()
                    error("cancelled operation cannot continue")
                }
            } catch (_: CancellationException) { propagated = true; throw CancellationException() }
        }
        runCurrent()
        assertTrue(owner.state.value.busy)
        page.cancel()
        advanceUntilIdle()
        assertTrue(propagated)
        assertTrue(page.isCancelled)
        assertFalse(owner.state.value.busy)
        val restored = storage.owner(dispatcher).state.value.receipt!!
        assertEquals(RikkaPhoneImportStatus.CANCELLED, restored.status)
        assertEquals(RikkaChatImportResult(2, 1, 4, 3), restored.result)
        assertTrue(restored.summary().contains("已有窗口会跳过"))
        assertFalse(restored.summary().contains("未改动"))
    }

    @Test fun typedCancellationUsesCommitCountEvenIfProgressCallbackNeverReachedUi() = runTest {
        val storage = Storage(); val owner = storage.owner(StandardTestDispatcher(testScheduler))
        try {
            owner.import { throw RikkaChatImportCancelledException(RikkaChatImportResult(1, 2, 3, 4)) }
            fail()
        } catch (_: RikkaChatImportCancelledException) { }
        assertEquals(RikkaChatImportResult(1, 2, 3, 4), owner.state.value.receipt!!.result)
        assertEquals(RikkaPhoneImportStatus.CANCELLED, owner.state.value.receipt!!.status)
    }

    @Test fun cancellingCopyRecordsZeroForThisOperationNotOldSuccess() = runTest {
        val storage = Storage(); val owner = storage.owner(StandardTestDispatcher(testScheduler))
        owner.import { RikkaChatImportResult(7, 0, 1, 0) }
        val oldId = owner.state.value.receipt!!.operationId
        try { owner.import { throw CancellationException("synthetic copying cancellation") }; fail() }
        catch (_: CancellationException) { }
        assertNotEquals(oldId, owner.state.value.receipt!!.operationId)
        assertEquals(RikkaChatImportResult(), owner.state.value.receipt!!.result)
        assertEquals(RikkaPhoneImportStatus.CANCELLED, owner.state.value.receipt!!.status)
    }

    @Test fun finalSuccessReceiptSurvivesPromptCancellationWhileReturningToPage() = runTest {
        val storage = Storage(); val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = storage.owner(dispatcher)
        lateinit var page: Job
        page = launch {
            try {
                owner.import {
                    page.cancel()
                    RikkaChatImportResult(3, 4, 5, 6)
                }
                fail("caller is cancelled")
            } catch (_: CancellationException) { }
        }
        advanceUntilIdle()
        assertTrue(page.isCancelled)
        assertEquals(RikkaPhoneImportStatus.COMPLETED, storage.owner(dispatcher).state.value.receipt!!.status)
        assertEquals(RikkaChatImportResult(3, 4, 5, 6), owner.state.value.receipt!!.result)
    }

    @Test fun busyOwnerRejectsOverlapAndLateOldCallbackCannotReplaceNewReceipt() = runTest {
        val storage = Storage(); val owner = storage.owner(StandardTestDispatcher(testScheduler))
        val unblock = CompletableDeferred<Unit>()
        var stale: ((RikkaChatImportResult) -> Unit)? = null
        val first = launch { owner.import { progress -> stale = progress; unblock.await(); RikkaChatImportResult(1) } }
        runCurrent()
        val firstId = owner.state.value.receipt!!.operationId
        try { owner.import { error("must not run overlapping copy/import") }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals(firstId, owner.state.value.receipt!!.operationId)
        unblock.complete(Unit)
        first.join()
        owner.import { progress ->
            stale!!(RikkaChatImportResult(99, 99))
            progress(RikkaChatImportResult(2, 1))
            RikkaChatImportResult(2, 1)
        }
        assertEquals(RikkaChatImportResult(2, 1), owner.state.value.receipt!!.result)
    }

    @Test fun restartedProcessMarksUnfinishedReceiptAsLowerBoundNotCompletedOrStillBusy() = runTest {
        val storage = Storage(); val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = storage.owner(dispatcher)
        val page = launch { owner.import { progress -> progress(RikkaChatImportResult(2)); CompletableDeferred<Unit>().await(); error("unreachable") } }
        runCurrent()
        val restarted = storage.owner(dispatcher).state.value
        assertFalse(restarted.busy)
        assertEquals(RikkaPhoneImportStatus.INTERRUPTED, restarted.receipt!!.status)
        assertEquals(2, restarted.receipt.result.imported)
        assertTrue(restarted.receipt.summary().contains("实际完成数可能更多"))
        page.cancel(); advanceUntilIdle()
    }

    @Test fun dismissIsDurableButDoesNotDiscardActiveOperation() = runTest {
        val storage = Storage(); val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = storage.owner(dispatcher)
        val page = launch { owner.import { CompletableDeferred<Unit>().await(); RikkaChatImportResult() } }
        runCurrent()
        owner.dismiss()
        assertTrue(owner.state.value.busy)
        assertNotNull(owner.state.value.receipt)
        page.cancel(); advanceUntilIdle()
        owner.dismiss()
        assertNull(storage.owner(dispatcher).state.value.receipt)
        assertFalse(storage.owner(dispatcher).state.value.storageWarning)
    }

    @Test fun storageFailureAfterCommitDoesNotReportZeroOrRunFurtherWindows() = runTest {
        val storage = Storage(); val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = storage.owner(dispatcher)
        var continued = false
        try {
            owner.import { progress ->
                storage.failWrites = true
                progress(RikkaChatImportResult(1, 0, 2, 0))
                continued = true
                RikkaChatImportResult()
            }
            fail()
        } catch (_: IllegalStateException) { }
        assertFalse(continued)
        assertTrue(owner.state.value.storageWarning)
        assertEquals(RikkaChatImportResult(1, 0, 2, 0), owner.state.value.receipt!!.result)
        assertEquals(RikkaPhoneImportStatus.FAILED, owner.state.value.receipt!!.status)
    }

    @Test fun failureReceiptNeverSerializesSourceTextPathsOrExceptionMessages() = runTest {
        val storage = Storage(); val owner = storage.owner(StandardTestDispatcher(testScheduler))
        try { owner.import { throw RikkaPartialImportException(RikkaChatImportResult(1), "synthetic-secret-path-and-text") }; fail() }
        catch (_: RikkaPartialImportException) { }
        assertEquals(RikkaPhoneImportStatus.FAILED, owner.state.value.receipt!!.status)
        assertFalse(storage.raw!!.contains("synthetic-secret"))
        assertFalse(storage.raw!!.contains("source"))
        assertTrue(storage.raw!!.length < 1024)
    }

    @Test fun finalReceiptWriteFailureKeepsActualSuccessAndWarnsWithoutClaimingItWasPersisted() = runTest {
        val storage = Storage(); val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = storage.owner(dispatcher)
        val committed = RikkaChatImportResult(3, 2, 4, 1)
        val result = owner.import { progress ->
            progress(committed)
            // All source windows have completed, but the final status cannot be persisted.
            storage.failWrites = true
            committed
        }
        assertEquals(committed, result)
        assertEquals(RikkaPhoneImportStatus.COMPLETED, owner.state.value.receipt!!.status)
        assertEquals(committed, owner.state.value.receipt!!.result)
        assertTrue(owner.state.value.storageWarning)
        assertFalse(owner.state.value.busy)
        assertFalse(owner.state.value.receipt!!.summary().contains("未全部完成"))
        val restarted = storage.owner(dispatcher).state.value
        assertEquals(RikkaPhoneImportStatus.INTERRUPTED, restarted.receipt!!.status)
        assertEquals(committed, restarted.receipt.result)
        assertTrue(restarted.receipt.summary().contains("实际完成数可能更多"))
    }

    @Test fun malformedReceiptShowsWarningWithoutChangingItOnRead() = runTest {
        val storage = Storage().apply { raw = "not valid receipt" }
        val owner = storage.owner(StandardTestDispatcher(testScheduler))
        assertTrue(owner.state.value.storageWarning)
        assertNull(owner.state.value.receipt)
        assertEquals("not valid receipt", storage.raw)
        assertEquals(0, storage.writes)
    }
}
