package me.rerere.rikkahub.data.orbis.contact

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic records and an in-memory fault-injection disk only; never rings, posts, or dials. */
class IncomingCallLedgerTest {
    private val assistant = "11111111-1111-4111-8111-111111111111"
    private val otherAssistant = "22222222-2222-4222-8222-222222222222"
    private val conversation = "33333333-3333-4333-8333-333333333333"
    private val call = "44444444-4444-4444-8444-444444444444"

    private enum class WriteFault { NONE, BEFORE_COMMIT, AFTER_COMMIT, VERIFY_READ, VERIFY_MISMATCH }

    private class Storage : IncomingCallStorage {
        override val lockKey = UUID.randomUUID().toString()
        val records = linkedMapOf<String, String>()
        var writes = 0
        var nextWrite = WriteFault.NONE
        private var nextRead = WriteFault.NONE
        override fun ids() = records.keys.toList()
        override fun read(id: String): String? {
            val fault = nextRead
            nextRead = WriteFault.NONE
            if (fault == WriteFault.VERIFY_READ) throw IOException("synthetic verify read failed")
            if (fault == WriteFault.VERIFY_MISMATCH) return records[id]?.plus(" ")
            return records[id]
        }
        override fun write(id: String, value: String) {
            // Match AndroidIncomingCallStorage's UTF-8 byte boundary.
            require(value.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
            val fault = nextWrite
            nextWrite = WriteFault.NONE
            if (fault == WriteFault.BEFORE_COMMIT) throw IOException("synthetic write failed")
            records[id] = value
            writes++
            if (fault == WriteFault.AFTER_COMMIT) throw IOException("synthetic uncertain commit")
            if (fault in setOf(WriteFault.VERIFY_READ, WriteFault.VERIFY_MISMATCH)) nextRead = fault
        }
    }

    private fun attempt(startedAt: Long = 100, owner: String = assistant) = IncomingCallAttempt(
        id = UUID.randomUUID().toString(), assistantId = owner, conversationId = conversation,
        reason = "Synthetic invitation", startedAtMs = startedAt, ringSeconds = 15,
    )

    private suspend fun terminal(
        ledger: IncomingCallLedger,
        attempt: IncomingCallAttempt,
        outcome: IncomingCallOutcome,
        finished: Long = attempt.startedAtMs + 10,
    ): IncomingCallAttempt {
        ledger.create(attempt)
        if (outcome == IncomingCallOutcome.CONNECTED) {
            ledger.update(attempt.id) { it.copy(outcome = IncomingCallOutcome.CONNECTING) }
        }
        return ledger.update(attempt.id) { it.copy(
            outcome = outcome, finishedAtMs = finished,
            connectedCallId = call.takeIf { outcome == IncomingCallOutcome.CONNECTED },
            failureCode = "synthetic_failure".takeIf { outcome == IncomingCallOutcome.FAILED },
        ) }
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Throwable) { return error }
        error("Expected operation to reject")
    }

    @Test fun allFourTerminalOutcomesRoundTripWithTheirExactReceipts() = runTest {
        val ledger = IncomingCallLedger(Storage())
        for (outcome in listOf(IncomingCallOutcome.CONNECTED, IncomingCallOutcome.REJECTED,
            IncomingCallOutcome.NO_RESPONSE, IncomingCallOutcome.FAILED)) {
            val expected = terminal(ledger, attempt(), outcome)
            assertEquals(expected, ledger.get(expected.id))
            assertEquals(outcome, expected.outcome)
            assertEquals(110L, expected.finishedAtMs)
            assertEquals(if (outcome == IncomingCallOutcome.CONNECTED) call else null, expected.connectedCallId)
            assertFalse(expected.fallbackAttempted)
            assertFalse(expected.fallbackPosted)
        }
        assertEquals(4, ledger.list().size)
    }

    @Test fun terminalOutcomeCannotBeRewrittenByLateCallbacks() = runTest {
        val ledger = IncomingCallLedger(Storage())
        val outcomes = listOf(IncomingCallOutcome.CONNECTED, IncomingCallOutcome.REJECTED,
            IncomingCallOutcome.NO_RESPONSE, IncomingCallOutcome.FAILED)
        for (outcome in outcomes) {
            val final = terminal(ledger, attempt(), outcome)
            for (replacement in IncomingCallOutcome.entries.filter { it != outcome }) {
                assertTrue(failure { ledger.update(final.id) { it.copy(outcome = replacement) } } is IllegalArgumentException)
                assertEquals(final, ledger.get(final.id))
            }
        }
    }

    @Test fun terminalTimeAndConnectedCallIdentityRemainImmutable() = runTest {
        val ledger = IncomingCallLedger(Storage())
        val final = terminal(ledger, attempt(), IncomingCallOutcome.CONNECTED)
        failure { ledger.update(final.id) { it.copy(finishedAtMs = 111) } }
        failure { ledger.update(final.id) { it.copy(finishedAtMs = null) } }
        failure { ledger.update(final.id) { it.copy(connectedCallId = UUID.randomUUID().toString()) } }
        failure { ledger.update(final.id) { it.copy(connectedCallId = null) } }
        assertEquals(final, ledger.get(final.id))
    }

    @Test fun terminalFailureReasonAndMuteChoiceCannotBeRewritten() = runTest {
        val ledger = IncomingCallLedger(Storage())
        for (outcome in listOf(IncomingCallOutcome.CONNECTED, IncomingCallOutcome.REJECTED,
            IncomingCallOutcome.NO_RESPONSE, IncomingCallOutcome.FAILED)) {
            val final = terminal(ledger, attempt(), outcome)
            failure { ledger.update(final.id) { it.copy(mutedAnswer = !it.mutedAnswer) } }
            failure { ledger.update(final.id) { it.copy(failureCode = "later_failure") } }
            if (final.failureCode != null) failure { ledger.update(final.id) { it.copy(failureCode = null) } }
            assertEquals(final, ledger.get(final.id))
        }
    }

    @Test fun recordedFallbackSpeechIsImmutableAndMustUseAnExplicitOutcome() = runTest {
        val ledger = IncomingCallLedger(Storage())
        val unanswered = terminal(ledger, attempt(), IncomingCallOutcome.NO_RESPONSE)
        ledger.update(unanswered.id) { it.copy(fallbackAttempted = true, fallbackPosted = true) }
        failure { ledger.update(unanswered.id) { it.copy(fallbackSpeech = "human_definitely_heard") } }
        val final = ledger.update(unanswered.id) { it.copy(fallbackSpeech = "skipped") }
        failure { ledger.update(unanswered.id) { it.copy(fallbackSpeech = "played") } }
        failure { ledger.update(unanswered.id) { it.copy(fallbackSpeech = null) } }
        assertEquals(final, ledger.get(final.id))
    }

    @Test fun attemptIdentityCannotMoveBetweenOwnersOrConversationsOrChangeOriginalReason() = runTest {
        val ledger = IncomingCallLedger(Storage())
        val original = ledger.create(attempt())
        val changes: List<(IncomingCallAttempt) -> IncomingCallAttempt> = listOf(
            { it.copy(id = UUID.randomUUID().toString()) },
            { it.copy(assistantId = otherAssistant) },
            { it.copy(conversationId = UUID.randomUUID().toString()) },
            { it.copy(reason = "Rewritten reason") },
            { it.copy(startedAtMs = 101) },
            { it.copy(ringSeconds = 30) },
        )
        changes.forEach { transform -> failure { ledger.update(original.id, transform) } }
        assertEquals(original, ledger.get(original.id))
        assertEquals(listOf(original), ledger.list())
    }

    @Test fun unchangedUpdateDoesNotRewriteDiskAndDuplicateCreateCannotOverwrite() = runTest {
        val storage = Storage()
        val ledger = IncomingCallLedger(storage)
        val original = ledger.create(attempt())
        assertEquals(original, ledger.update(original.id) { it })
        assertEquals(1, storage.writes)
        failure { ledger.create(original.copy(reason = "Overwrite")) }
        assertEquals(original, ledger.get(original.id))
        assertEquals(1, storage.writes)
    }

    @Test fun connectingCannotReturnToRingingOrClaimConnectedWithoutCallId() = runTest {
        val ledger = IncomingCallLedger(Storage())
        val original = ledger.create(attempt())
        val connecting = ledger.update(original.id) { it.copy(outcome = IncomingCallOutcome.CONNECTING, mutedAnswer = true) }
        failure { ledger.update(original.id) { it.copy(outcome = IncomingCallOutcome.RINGING) } }
        failure { ledger.update(original.id) { it.copy(outcome = IncomingCallOutcome.CONNECTED, finishedAtMs = 110) } }
        assertEquals(connecting, ledger.get(original.id))
        assertTrue(connecting.mutedAnswer)
    }

    @Test fun terminalRequiresFinishTimeAndPendingCannotHaveOne() = runTest {
        val ledger = IncomingCallLedger(Storage())
        val original = ledger.create(attempt())
        failure { ledger.update(original.id) { it.copy(outcome = IncomingCallOutcome.REJECTED) } }
        failure { ledger.update(original.id) { it.copy(finishedAtMs = 110) } }
        failure { ledger.update(original.id) { it.copy(outcome = IncomingCallOutcome.FAILED, finishedAtMs = 99) } }
        assertEquals(original, ledger.get(original.id))
    }

    @Test fun onlyRejectedAndUnansweredCallsStartOwnerScopedCooldown() = runTest {
        for (outcome in listOf(IncomingCallOutcome.CONNECTED, IncomingCallOutcome.REJECTED,
            IncomingCallOutcome.NO_RESPONSE, IncomingCallOutcome.FAILED)) {
            val ledger = IncomingCallLedger(Storage())
            terminal(ledger, attempt(), outcome, finished = 200)
            val expected = if (outcome in setOf(IncomingCallOutcome.REJECTED, IncomingCallOutcome.NO_RESPONSE)) 900L else 0L
            assertEquals(expected, ledger.cooldownRemaining(assistant, now = 300, cooldownMs = 1000))
            assertEquals(0L, ledger.cooldownRemaining(otherAssistant, now = 300, cooldownMs = 1000))
        }
    }

    @Test fun cooldownExpiresAndClockRollbackDoesNotBypassIt() = runTest {
        val ledger = IncomingCallLedger(Storage())
        terminal(ledger, attempt(), IncomingCallOutcome.REJECTED, finished = 200)
        assertEquals(1000L, ledger.cooldownRemaining(assistant, now = 150, cooldownMs = 1000))
        assertEquals(1L, ledger.cooldownRemaining(assistant, now = 1199, cooldownMs = 1000))
        assertEquals(0L, ledger.cooldownRemaining(assistant, now = 1200, cooldownMs = 1000))
        assertEquals(0L, ledger.cooldownRemaining(assistant, now = 5000, cooldownMs = 1000))
        assertEquals(0L, ledger.cooldownRemaining(assistant, now = 200, cooldownMs = 0))
        failure { ledger.cooldownRemaining(assistant, now = 200, cooldownMs = -1) }
    }

    @Test fun latestRejectedAttemptDefinesCooldownNotNewerFailureOrOtherOwner() = runTest {
        val ledger = IncomingCallLedger(Storage())
        terminal(ledger, attempt(100), IncomingCallOutcome.REJECTED, 200)
        terminal(ledger, attempt(300), IncomingCallOutcome.NO_RESPONSE, 400)
        terminal(ledger, attempt(500), IncomingCallOutcome.FAILED, 600)
        terminal(ledger, attempt(700, otherAssistant), IncomingCallOutcome.REJECTED, 800)
        assertEquals(500L, ledger.cooldownRemaining(assistant, now = 900, cooldownMs = 1000))
        assertEquals(900L, ledger.cooldownRemaining(otherAssistant, now = 900, cooldownMs = 1000))
    }

    @Test fun fallbackRequiresUnansweredThenAttemptBeforePostedThenSpeech() = runTest {
        val ledger = IncomingCallLedger(Storage())
        val unanswered = terminal(ledger, attempt(), IncomingCallOutcome.NO_RESPONSE)
        failure { ledger.update(unanswered.id) { it.copy(fallbackPosted = true) } }
        failure { ledger.update(unanswered.id) { it.copy(fallbackSpeech = "played") } }
        val attempted = ledger.update(unanswered.id) { it.copy(fallbackAttempted = true) }
        assertFalse(attempted.fallbackPosted)
        failure { ledger.update(unanswered.id) { it.copy(fallbackSpeech = "played") } }
        val posted = ledger.update(unanswered.id) { it.copy(fallbackPosted = true) }
        assertNull(posted.fallbackSpeech)
        val spoken = ledger.update(unanswered.id) { it.copy(fallbackSpeech = "skipped") }
        assertEquals("skipped", spoken.fallbackSpeech)
        assertEquals(IncomingCallOutcome.NO_RESPONSE, spoken.outcome)
    }

    @Test fun rejectedConnectedAndFailedCannotBecomeFallbackCandidates() = runTest {
        val ledger = IncomingCallLedger(Storage())
        for (outcome in listOf(IncomingCallOutcome.CONNECTED, IncomingCallOutcome.REJECTED, IncomingCallOutcome.FAILED)) {
            val final = terminal(ledger, attempt(), outcome)
            failure { ledger.update(final.id) { it.copy(fallbackAttempted = true) } }
            assertEquals(final, ledger.get(final.id))
        }
        val pending = ledger.create(attempt())
        failure { ledger.update(pending.id) { it.copy(fallbackAttempted = true) } }
    }

    @Test fun fallbackAttemptAndPostLatchesCannotBeResetForReplay() = runTest {
        val ledger = IncomingCallLedger(Storage())
        val unanswered = terminal(ledger, attempt(), IncomingCallOutcome.NO_RESPONSE)
        ledger.update(unanswered.id) { it.copy(fallbackAttempted = true) }
        failure { ledger.update(unanswered.id) { it.copy(fallbackAttempted = false) } }
        val posted = ledger.update(unanswered.id) { it.copy(fallbackPosted = true) }
        failure { ledger.update(unanswered.id) { it.copy(fallbackPosted = false) } }
        failure { ledger.update(unanswered.id) { it.copy(fallbackAttempted = false, fallbackPosted = false) } }
        assertEquals(posted, ledger.get(posted.id))
    }

    @Test fun atomicUpdateCanClaimFallbackOnlyOnceAcrossLedgerInstances() = runTest {
        val storage = Storage()
        val first = IncomingCallLedger(storage)
        val second = IncomingCallLedger(storage)
        val unanswered = terminal(first, attempt(), IncomingCallOutcome.NO_RESPONSE)
        val claims = (1..20).map { index -> async {
            var claimed = false
            (if (index % 2 == 0) first else second).update(unanswered.id) {
                if (it.fallbackAttempted) it else { claimed = true; it.copy(fallbackAttempted = true) }
            }
            claimed
        } }.awaitAll()
        assertEquals(1, claims.count { it })
        assertTrue(first.get(unanswered.id)!!.fallbackAttempted)
        assertFalse(first.get(unanswered.id)!!.fallbackPosted)
    }

    @Test fun recoveryFailsPendingCallsWithoutDialingOrCreatingFallback() = runTest {
        val storage = Storage()
        val before = IncomingCallLedger(storage)
        val ringing = before.create(attempt(100))
        val connecting = before.create(attempt(200))
        before.update(connecting.id) { it.copy(outcome = IncomingCallOutcome.CONNECTING, mutedAnswer = true) }
        val after = IncomingCallLedger(storage)
        after.recoverInterrupted(500)
        for (id in listOf(ringing.id, connecting.id)) {
            val recovered = after.get(id)!!
            assertEquals(IncomingCallOutcome.FAILED, recovered.outcome)
            assertEquals("process_interrupted", recovered.failureCode)
            assertEquals(500L, recovered.finishedAtMs)
            assertFalse(recovered.fallbackAttempted)
            assertFalse(recovered.fallbackPosted)
            assertNull(recovered.fallbackSpeech)
            assertNull(recovered.connectedCallId)
        }
        assertTrue(after.get(connecting.id)!!.mutedAnswer)
        val writes = storage.writes
        after.recoverInterrupted(600)
        assertEquals(writes, storage.writes)
        assertEquals(0L, after.cooldownRemaining(assistant, 600, 1000))
    }

    @Test fun recoveryPreservesAllTerminalReceiptsIncludingUncertainFallbackIntent() = runTest {
        val storage = Storage()
        val ledger = IncomingCallLedger(storage)
        terminal(ledger, attempt(100), IncomingCallOutcome.CONNECTED)
        terminal(ledger, attempt(200), IncomingCallOutcome.REJECTED)
        terminal(ledger, attempt(300), IncomingCallOutcome.FAILED)
        val unanswered = terminal(ledger, attempt(400), IncomingCallOutcome.NO_RESPONSE)
        ledger.update(unanswered.id) { it.copy(fallbackAttempted = true) }
        val original = ledger.list()
        val writes = storage.writes
        val restored = IncomingCallLedger(storage)
        restored.recoverInterrupted(999)
        assertEquals(original, restored.list())
        assertEquals(writes, storage.writes)
        assertFalse(restored.get(unanswered.id)!!.fallbackPosted)
    }

    @Test fun recoveryTimeCannotPrecedeAttemptEvenAfterWallClockRollback() = runTest {
        val ledger = IncomingCallLedger(Storage())
        val future = ledger.create(attempt(500))
        ledger.recoverInterrupted(100)
        assertEquals(500L, ledger.get(future.id)!!.finishedAtMs)
    }

    @Test fun failedWriteBeforeCommitRetainsPreviousState() = runTest {
        val storage = Storage()
        val ledger = IncomingCallLedger(storage)
        val original = ledger.create(attempt())
        storage.nextWrite = WriteFault.BEFORE_COMMIT
        assertTrue(failure { ledger.update(original.id) { it.copy(outcome = IncomingCallOutcome.REJECTED, finishedAtMs = 110) } } is IOException)
        assertEquals(original, ledger.get(original.id))
        assertEquals(1, storage.writes)
    }

    @Test fun writeThenThrowDoesNotClaimSuccessAndDurableTerminalCannotBeOverwritten() = runTest {
        val storage = Storage()
        val ledger = IncomingCallLedger(storage)
        val original = ledger.create(attempt())
        storage.nextWrite = WriteFault.AFTER_COMMIT
        assertTrue(failure { ledger.update(original.id) { it.copy(outcome = IncomingCallOutcome.REJECTED, finishedAtMs = 110) } } is IOException)
        val uncertain = ledger.get(original.id)!!
        assertEquals(IncomingCallOutcome.REJECTED, uncertain.outcome)
        failure { ledger.update(original.id) { it.copy(outcome = IncomingCallOutcome.NO_RESPONSE) } }
        IncomingCallLedger(storage).recoverInterrupted(500)
        assertEquals(uncertain, ledger.get(original.id))
    }

    @Test fun uncertainCreateCannotBeRetriedAsAnOverwriteAndRecoveryDoesNotRingIt() = runTest {
        val storage = Storage()
        val ledger = IncomingCallLedger(storage)
        val original = attempt()
        storage.nextWrite = WriteFault.AFTER_COMMIT
        failure { ledger.create(original) }
        assertEquals(original, ledger.get(original.id))
        failure { ledger.create(original) }
        ledger.recoverInterrupted(200)
        assertEquals(IncomingCallOutcome.FAILED, ledger.get(original.id)!!.outcome)
        assertFalse(ledger.get(original.id)!!.fallbackAttempted)
    }

    @Test fun verifyReadFailureAndMismatchRemainUncertainNotSuccessfulOrRolledBack() = runTest {
        for (fault in listOf(WriteFault.VERIFY_READ, WriteFault.VERIFY_MISMATCH)) {
            val storage = Storage()
            val ledger = IncomingCallLedger(storage)
            val original = ledger.create(attempt())
            storage.nextWrite = fault
            failure { ledger.update(original.id) { it.copy(outcome = IncomingCallOutcome.NO_RESPONSE, finishedAtMs = 110) } }
            val retained = ledger.get(original.id)!!
            assertEquals(IncomingCallOutcome.NO_RESPONSE, retained.outcome)
            assertFalse(retained.fallbackAttempted)
            ledger.recoverInterrupted(500)
            assertEquals(retained, ledger.get(original.id))
        }
    }

    @Test fun uncertainFallbackIntentNeverPostsOrReplaysAfterRestart() = runTest {
        for (fault in listOf(WriteFault.AFTER_COMMIT, WriteFault.VERIFY_READ, WriteFault.VERIFY_MISMATCH)) {
            val storage = Storage()
            val ledger = IncomingCallLedger(storage)
            val unanswered = terminal(ledger, attempt(), IncomingCallOutcome.NO_RESPONSE)
            var posted = 0
            storage.nextWrite = fault
            failure {
                ledger.update(unanswered.id) { it.copy(fallbackAttempted = true) }
                posted++ // Only reachable after a verified intent checkpoint.
            }
            assertEquals(0, posted)
            val restored = IncomingCallLedger(storage)
            restored.recoverInterrupted(1000)
            val retained = restored.get(unanswered.id)!!
            assertTrue(retained.fallbackAttempted)
            assertFalse(retained.fallbackPosted)
            assertNull(retained.fallbackSpeech)
            assertEquals(0, posted)
        }
    }

    @Test fun uncertainPostReceiptRemainsRecordedAndRecoveryNeverRetriesTheEffect() = runTest {
        val storage = Storage()
        val ledger = IncomingCallLedger(storage)
        val unanswered = terminal(ledger, attempt(), IncomingCallOutcome.NO_RESPONSE)
        ledger.update(unanswered.id) { it.copy(fallbackAttempted = true) }
        var posted = 0
        posted++ // Synthetic successful native notification, before receipt persistence.
        storage.nextWrite = WriteFault.AFTER_COMMIT
        failure { ledger.update(unanswered.id) { it.copy(fallbackPosted = true) } }
        val restored = IncomingCallLedger(storage)
        restored.recoverInterrupted(1000)
        assertTrue(restored.get(unanswered.id)!!.fallbackAttempted)
        assertTrue(restored.get(unanswered.id)!!.fallbackPosted)
        assertNull(restored.get(unanswered.id)!!.fallbackSpeech)
        assertEquals(1, posted)
    }

    @Test fun partialRecoveryWriteFailureCanResumeWithoutRewritingCompletedRecovery() = runTest {
        val storage = Storage()
        val ledger = IncomingCallLedger(storage)
        val older = ledger.create(attempt(100))
        val newer = ledger.create(attempt(200))
        storage.nextWrite = WriteFault.AFTER_COMMIT
        failure { ledger.recoverInterrupted(500) }
        assertEquals(IncomingCallOutcome.FAILED, ledger.get(newer.id)!!.outcome)
        assertEquals(IncomingCallOutcome.RINGING, ledger.get(older.id)!!.outcome)
        ledger.recoverInterrupted(600)
        assertEquals(500L, ledger.get(newer.id)!!.finishedAtMs)
        assertEquals(600L, ledger.get(older.id)!!.finishedAtMs)
        assertTrue(ledger.list().all { it.outcome == IncomingCallOutcome.FAILED && !it.fallbackAttempted })
    }

    @Test fun historyIsOwnerFilteredPagedNewestFirstAndAppendPreserving() = runTest {
        val ledger = IncomingCallLedger(Storage())
        val first = ledger.create(attempt(100))
        val second = ledger.create(attempt(200))
        val other = ledger.create(attempt(300, otherAssistant))
        val newest = ledger.create(attempt(400))
        assertEquals(listOf(newest, other, second, first), ledger.list())
        assertEquals(listOf(newest, second, first), ledger.list(assistant))
        assertEquals(listOf(second), ledger.list(assistant, offset = 1, limit = 1))
        assertEquals(emptyList<IncomingCallAttempt>(), ledger.list(offset = 100))
        failure { ledger.list(offset = -1) }
        failure { ledger.list(limit = 0) }
        failure { ledger.list(limit = 101) }
        assertEquals(4, ledger.list().size)
    }

    @Test fun malformedOrOversizedRecordFailsClosedWithoutSilentlyDiscardingHistory() = runTest {
        val storage = Storage()
        val ledger = IncomingCallLedger(storage)
        val good = ledger.create(attempt())
        val broken = UUID.randomUUID().toString()
        storage.records[broken] = "{"
        failure { ledger.list() }
        assertEquals(good, ledger.get(good.id))
        assertEquals("{", storage.records[broken])
        storage.records[broken] = "x".repeat(32 * 1024 + 1)
        failure { ledger.get(broken) }
        assertEquals(2, storage.records.size)
    }

    @Test fun invalidRecordFieldsAndPathLikeIdsAreRejectedBeforeWrite() = runTest {
        val storage = Storage()
        val ledger = IncomingCallLedger(storage)
        val base = attempt()
        for (bad in listOf(base.copy(id = "../outside"), base.copy(assistantId = ""),
            base.copy(conversationId = " "), base.copy(reason = ""), base.copy(reason = "x".repeat(2001)),
            base.copy(ringSeconds = 4), base.copy(ringSeconds = 61), base.copy(startedAtMs = -1),
            base.copy(outcome = IncomingCallOutcome.FAILED, finishedAtMs = 110))) {
            failure { ledger.create(bad) }
        }
        assertEquals(0, storage.writes)
        failure { ledger.get("../../outside") }
        failure { ledger.update(base.id) { it } }
    }

    @Test fun boundedDiskRejectsOversizedUtf8WithoutAStoredRecord() = runTest {
        val storage = Storage()
        val ledger = IncomingCallLedger(storage)
        val base = attempt()
        failure { ledger.create(base.copy(assistantId = "字".repeat(12000))) }
        assertNull(ledger.get(base.id))
        assertEquals(0, storage.writes)
    }
}
