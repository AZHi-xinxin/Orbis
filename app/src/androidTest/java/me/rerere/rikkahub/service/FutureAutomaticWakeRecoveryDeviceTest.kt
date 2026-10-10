package me.rerere.rikkahub.service

import android.app.Application
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.FreshHumanInputRecoveryStore
import me.rerere.rikkahub.data.orbis.FreshHumanRecoveryStatus
import me.rerere.rikkahub.data.orbis.OrbisEventBinding
import me.rerere.rikkahub.data.orbis.OrbisEventInbox
import me.rerere.rikkahub.data.orbis.OrbisIncomingEvent
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import me.rerere.rikkahub.data.orbis.OrbisQueuePauseStore
import me.rerere.rikkahub.data.orbis.QueuePauseStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Synthetic AtomicFile/store/queue integration only: never ChatService, Koin, real history or models. */
@RunWith(AndroidJUnit4::class)
class FutureAutomaticWakeRecoveryDeviceTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(it.targetContext.packageName == "org.orbis.agent.dev")
            check(!LcExternalRecoveryGate.isAllowed())
        }
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        private val root = Files.createTempDirectory(cache.toPath(), "future-wake-isolated-").toFile().canonicalFile
        val writes = mutableListOf<String>()
        var failWrite: Pair<String, Int>? = null
        var failAfterCommit: Pair<String, Int>? = null
        var dropWrites: Set<String> = emptySet()
        var beforeWrite: (String, Int) -> Unit = { _, _ -> }

        private fun atomic(name: String): AtomicFile {
            check(name in FILES)
            return AtomicFile(File(root, name))
        }

        fun read(name: String): String? = atomic(name).let {
            if (it.baseFile.exists() || File(it.baseFile.path + ".bak").exists())
                it.openRead().bufferedReader().use { stream -> stream.readText() } else null
        }

        private fun write(name: String, text: String) {
            writes += name
            val ordinal = writes.count { it == name }
            beforeWrite(name, ordinal)
            if (failWrite == (name to ordinal)) error("synthetic_atomic_write_failure")
            if (name in dropWrites) return
            val file = atomic(name)
            val stream = file.startWrite()
            try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
            // The durable commit is already complete: this is NOT an AtomicFile rollback failure.
            if (failAfterCommit == (name to ordinal)) error("synthetic_commit_acknowledgement_lost")
        }

        fun pause(name: String) = OrbisQueuePauseStore(read = { read(name) }, write = { write(name, it) })
        fun fresh() = FreshHumanInputRecoveryStore(pause("fresh.json"))
        fun inbox() = OrbisEventInbox(read = { read("inbox.json") }, write = { write("inbox.json", it) })
        fun futureAllowedAfterRestart(): Boolean =
            pause("guard.json").status(CONVERSATION) == QueuePauseStatus.UNPAUSED &&
                pause("automatic.json").status(CONVERSATION) == QueuePauseStatus.UNPAUSED &&
                fresh().status(CONVERSATION, ASSISTANT) == FreshHumanRecoveryStatus.NONE

        override fun close() {
            check(root.parentFile == cache && root.name.startsWith("future-wake-isolated-") &&
                !Files.isSymbolicLink(root.toPath()))
            val allowed = FILES.flatMap { listOf(it, "$it.bak", "$it.new") }
            checkNotNull(root.listFiles()).forEach {
                check(it.name in allowed && it.isFile && it.canonicalFile.parentFile == root &&
                    !Files.isSymbolicLink(it.toPath()))
                check(it.delete())
            }
            check(root.delete())
        }
    }

    private class Scenario(val files: Fixture) {
        val binding = OrbisEventBinding(ASSISTANT, CONVERSATION)
        val inbox = files.inbox()
        val guard = files.pause("guard.json")
        val automaticHold = files.pause("automatic.json")
        val fresh = files.fresh()
        val human = MessageQueue(initiallyPaused = true)
        val automatic = AutomaticWakeQueue()
        val oldAccepted: OrbisInboxEvent
        val oldQueued: OrbisInboxEvent
        val unknown: OrbisInboxEvent
        val previousEventIds: Set<String>
        private val previousHumanIds: Set<Uuid>

        init {
            files.pause("human.json").pause(CONVERSATION)
            automaticHold.pause(CONVERSATION, "unknown_tool_result")
            fresh.detachPreviousTurn(CONVERSATION, ASSISTANT)
            inbox.bind(setOf(SOURCE), binding)
            oldAccepted = accept("old-accepted", 1_000L)
            oldQueued = accept("old-queued", 1_001L).also { inbox.mark(it.id, "queued") }
            unknown = accept("old-unknown", 1_002L).also { inbox.mark(it.id, "unknown", "unknown_tool_result") }
            previousEventIds = setOf(oldAccepted.id, oldQueued.id)
            human.enqueue(listOf(UIMessagePart.Text("synthetic previous human input")))
            previousHumanIds = human.state.value.messages.map { it.id }.toSet()
            enqueue(oldAccepted)
            enqueue(oldQueued)
            files.writes.clear()
        }

        fun accept(id: String, now: Long): OrbisInboxEvent = inbox.accept(
            OrbisIncomingEvent(id, SOURCE, "synthetic event $id"), binding, now
        ).first

        fun enqueue(event: OrbisInboxEvent) = automatic.enqueue(
            listOf(UIMessagePart.Text(event.text)), true, Uuid.parse(event.id), event.id
        )

        fun recover(stillOwner: () -> Boolean = { true }): Boolean = commitFutureAutomaticWakeRecovery(
            stillOwner = stillOwner,
            establishGuard = { guard.pause(CONVERSATION, GUARD_REASON) },
            preservePreviousInputs = {
                human.holdInputsForRecovery(previousHumanIds)
                automatic.holdAllForRecovery(previousEventIds)
            },
            suppressPreviousEvents = {
                previousEventIds.forEach { inbox.mark(it, "suppressed", "explicit_future_recovery") }
            },
            verifyPreviousEvents = { inbox.verifySuppressed(CONVERSATION, previousEventIds) },
            clearFreshRestriction = { fresh.clearAfterConfirmedIdle(CONVERSATION, ASSISTANT) },
            acknowledgeAutomaticHold = {
                automaticHold.pauseReason(CONVERSATION)?.let {
                    check(it == "unknown_tool_result")
                    check(automaticHold.resumeIfReason(CONVERSATION, it))
                }
            },
            releaseGuard = { check(guard.resumeIfReason(CONVERSATION, GUARD_REASON)) },
        )
    }

    @Test fun recoverySurvivesRestartAndOnlyFutureEventCanLeaveAutomaticLane() {
        Fixture().use { f ->
            val s = Scenario(f)
            val originalHuman = s.human.state.value.messages.single()
            val originalUnknown = s.inbox.get(s.unknown.id)
            assertTrue(s.recover())
            assertTrue(f.futureAllowedAfterRestart())
            assertTrue(f.pause("human.json").isPaused(CONVERSATION))
            val heldHuman = s.human.state.value.messages.single()
            assertEquals(originalHuman.id, heldHuman.id)
            assertEquals(originalHuman.parts, heldHuman.parts)
            assertNotNull(heldHuman.recoveryHeldReason)
            assertTrue(s.human.state.value.paused)
            assertTrue(s.automatic.pending.all { it.recoveryHeldReason != null })
            val reopened = f.inbox()
            s.previousEventIds.forEach { assertEquals("suppressed", reopened.get(it)!!.state) }
            assertEquals(originalUnknown, reopened.get(s.unknown.id))
            val future = reopened.accept(OrbisIncomingEvent("future", SOURCE, "synthetic future only"), s.binding, 2_000L).first
            s.enqueue(future)
            assertEquals(future.id, takeNextConversationInput(s.human, s.automatic, false, false,
                f.futureAllowedAfterRestart())!!.orbisEventId)
            assertNull(takeNextConversationInput(s.human, s.automatic, false, false, true))
            assertEquals(s.previousEventIds, s.automatic.pending.map { it.orbisEventId }.toSet())
        }
    }

    @Test fun failedGuardWriteNeverMutatesOldInputsOrClearsExistingRestrictions() {
        Fixture().use { f ->
            val s = Scenario(f)
            val oldInbox = f.read("inbox.json")
            val oldHuman = s.human.state.value
            f.failWrite = "guard.json" to 1
            assertTrue(runCatching { s.recover() }.isFailure)
            assertEquals(listOf("guard.json"), f.writes)
            assertEquals(oldInbox, f.read("inbox.json"))
            assertEquals(oldHuman, s.human.state.value)
            assertEquals(FreshHumanRecoveryStatus.DETACHED, f.fresh().status(CONVERSATION, ASSISTANT))
            assertTrue(f.pause("automatic.json").isPaused(CONVERSATION))
            assertFalse(f.futureAllowedAfterRestart())
        }
    }

    @Test fun suppressionWriteFailureKeepsIndependentGuardAcrossRestart() {
        Fixture().use { f ->
            val s = Scenario(f)
            f.failWrite = "inbox.json" to 1
            assertTrue(runCatching { s.recover() }.isFailure)
            assertEquals(GUARD_REASON, f.pause("guard.json").pauseReason(CONVERSATION))
            assertEquals("accepted", f.inbox().get(s.oldAccepted.id)!!.state)
            assertEquals(FreshHumanRecoveryStatus.DETACHED, f.fresh().status(CONVERSATION, ASSISTANT))
            assertTrue(f.pause("automatic.json").isPaused(CONVERSATION))
            assertFalse(f.futureAllowedAfterRestart())
        }
    }

    @Test fun silentSuppressionDropCannotMistakePublishedMemoryForDurableReceipt() {
        Fixture().use { f ->
            val s = Scenario(f)
            f.dropWrites = setOf("inbox.json")
            val failure = runCatching { s.recover() }.exceptionOrNull()
            assertEquals("future_wake_suppression_not_durable", failure?.message)
            assertEquals("suppressed", s.inbox.get(s.oldAccepted.id)!!.state)
            assertEquals("accepted", f.inbox().get(s.oldAccepted.id)!!.state)
            assertFalse(s.inbox.verifySuppressed(CONVERSATION, s.previousEventIds))
            assertEquals(GUARD_REASON, f.pause("guard.json").pauseReason(CONVERSATION))
            assertEquals(FreshHumanRecoveryStatus.DETACHED, f.fresh().status(CONVERSATION, ASSISTANT))
            assertTrue(f.pause("automatic.json").isPaused(CONVERSATION))
            assertFalse(f.futureAllowedAfterRestart())
        }
    }

    @Test fun laterPreCommitWriteFailuresLeaveDurableGuardClosed() {
        listOf("fresh.json" to 1, "automatic.json" to 1, "guard.json" to 2).forEach { stage ->
            Fixture().use { f ->
                val s = Scenario(f)
                f.failWrite = stage
                assertTrue("stage $stage", runCatching { s.recover() }.isFailure)
                assertEquals(GUARD_REASON, f.pause("guard.json").pauseReason(CONVERSATION))
                s.previousEventIds.forEach { assertEquals("suppressed", f.inbox().get(it)!!.state) }
                assertFalse("stage $stage", f.futureAllowedAfterRestart())
                assertNull(takeNextConversationInput(s.human, s.automatic, false, false,
                    f.futureAllowedAfterRestart()))
            }
        }
    }

    @Test fun intermediateWriteThenThrowStillLeavesGuardClosedAfterRestart() {
        listOf("inbox.json" to 1, "fresh.json" to 1, "automatic.json" to 1).forEach { stage ->
            Fixture().use { f ->
                val s = Scenario(f)
                val oldUnknown = s.inbox.get(s.unknown.id)
                f.failAfterCommit = stage
                assertTrue("stage $stage", runCatching { s.recover() }.isFailure)
                assertEquals(GUARD_REASON, f.pause("guard.json").pauseReason(CONVERSATION))
                assertEquals(oldUnknown, f.inbox().get(s.unknown.id))
                assertFalse("stage $stage", f.futureAllowedAfterRestart())
                assertEquals(s.previousEventIds, s.automatic.pending.map { it.orbisEventId }.toSet())
                assertTrue(s.automatic.pending.all { it.recoveryHeldReason != null })
                assertTrue(s.human.state.value.paused)
                assertNull(takeNextConversationInput(s.human, s.automatic, false, false,
                    f.futureAllowedAfterRestart()))
            }
        }
    }

    @Test fun finalGuardWriteThenThrowIsCommittedReceiptUnknownAndNeverReplaysOrDispatches() {
        Fixture().use { f ->
            val s = Scenario(f)
            val oldUnknown = s.inbox.get(s.unknown.id)
            val oldHuman = s.human.state.value.messages.single()
            var prerequisitesVerifiedBeforeFinalCommit = false
            f.beforeWrite = { name, ordinal ->
                if (name == "guard.json" && ordinal == 2) {
                    assertTrue(s.inbox.verifySuppressed(CONVERSATION, s.previousEventIds))
                    assertEquals(FreshHumanRecoveryStatus.NONE, f.fresh().status(CONVERSATION, ASSISTANT))
                    assertEquals(QueuePauseStatus.UNPAUSED, f.pause("automatic.json").status(CONVERSATION))
                    assertEquals(GUARD_REASON, f.pause("guard.json").pauseReason(CONVERSATION))
                    prerequisitesVerifiedBeforeFinalCommit = true
                }
            }
            f.failAfterCommit = "guard.json" to 2
            assertTrue(runCatching { s.recover() }.isFailure)
            assertTrue(prerequisitesVerifiedBeforeFinalCommit)
            assertEquals(listOf("guard.json", "inbox.json", "inbox.json", "fresh.json", "automatic.json", "guard.json"),
                f.writes)

            // The same live store remains uncertain; a new process sees the completed transaction.
            assertEquals(QueuePauseStatus.PAUSED, s.guard.status(CONVERSATION))
            assertEquals(QueuePauseStatus.UNPAUSED, f.pause("guard.json").status(CONVERSATION))
            assertEquals(FreshHumanRecoveryStatus.NONE, f.fresh().status(CONVERSATION, ASSISTANT))
            assertEquals(QueuePauseStatus.UNPAUSED, f.pause("automatic.json").status(CONVERSATION))
            assertTrue(f.futureAllowedAfterRestart())
            val reopened = f.inbox()
            assertTrue(reopened.verifySuppressed(CONVERSATION, s.previousEventIds))
            assertEquals(oldUnknown, reopened.get(s.unknown.id))
            assertEquals(3, reopened.state.value.events.size)
            assertEquals(s.previousEventIds, s.automatic.pending.map { it.orbisEventId }.toSet())
            assertTrue(s.automatic.pending.all { it.recoveryHeldReason != null })
            assertEquals(oldHuman.id, s.human.state.value.messages.single().id)
            assertEquals(oldHuman.parts, s.human.state.value.messages.single().parts)
            assertTrue(s.human.state.value.paused)
            assertNull(takeNextConversationInput(s.human, s.automatic, false, false, true))

            // A duplicate stays terminal. Only an explicitly supplied new event can leave later.
            val duplicate = reopened.accept(OrbisIncomingEvent("old-accepted", SOURCE, s.oldAccepted.text), s.binding, 3_000L)
            assertTrue(duplicate.second)
            assertEquals("suppressed", duplicate.first.state)
            val future = reopened.accept(OrbisIncomingEvent("future-after-unknown-ack", SOURCE,
                "synthetic future after committed recovery"), s.binding, 3_001L).first
            s.enqueue(future)
            assertEquals(3, s.automatic.pending.size)
            assertEquals("accepted", reopened.get(future.id)!!.state)
            assertEquals(future.id, takeNextConversationInput(s.human, s.automatic, false, false,
                f.futureAllowedAfterRestart())!!.orbisEventId)
            assertNull(takeNextConversationInput(s.human, s.automatic, false, false, true))
        }
    }

    @Test fun ownerLossBeforeStartWritesNothingAndOwnerLossAfterSuppressionKeepsHold() {
        Fixture().use { f ->
            val s = Scenario(f)
            val before = FILES.associateWith(f::read)
            assertFalse(s.recover { false })
            assertTrue(f.writes.isEmpty())
            assertEquals(before, FILES.associateWith(f::read))
            var ownerChecks = 0
            assertFalse(s.recover { ++ownerChecks == 1 })
            assertEquals(2, ownerChecks)
            s.previousEventIds.forEach { assertEquals("suppressed", f.inbox().get(it)!!.state) }
            assertEquals(GUARD_REASON, f.pause("guard.json").pauseReason(CONVERSATION))
            assertEquals(FreshHumanRecoveryStatus.DETACHED, f.fresh().status(CONVERSATION, ASSISTANT))
            assertTrue(f.pause("automatic.json").isPaused(CONVERSATION))
        }
    }

    @Test fun repeatCommitDoesNotExpandCapturedOldSetOrReplayDuplicateReceipt() {
        Fixture().use { f ->
            val s = Scenario(f)
            assertTrue(s.recover())
            val future = s.accept("future", 2_000L)
            s.enqueue(future)
            assertTrue(s.recover())
            val reopened = f.inbox()
            val duplicate = reopened.accept(OrbisIncomingEvent("old-accepted", SOURCE, s.oldAccepted.text), s.binding, 3_000L)
            assertTrue(duplicate.second)
            assertEquals("suppressed", duplicate.first.state)
            assertEquals("accepted", reopened.get(future.id)!!.state)
            assertNull(s.automatic.pending.single { it.id.toString() == future.id }.recoveryHeldReason)
            assertEquals(future.id, takeNextConversationInput(s.human, s.automatic, false, false,
                f.futureAllowedAfterRestart())!!.orbisEventId)
            assertNull(takeNextConversationInput(s.human, s.automatic, false, false, true))
        }
    }

    @Test fun busyOrPendingApprovalStillBlocksFutureInputAfterSuccessfulRecovery() {
        Fixture().use { f ->
            val s = Scenario(f)
            assertTrue(s.recover())
            val future = s.accept("future-busy", 2_000L)
            s.enqueue(future)
            assertNull(takeNextConversationInput(s.human, s.automatic, true, false, f.futureAllowedAfterRestart()))
            assertNull(takeNextConversationInput(s.human, s.automatic, false, true, f.futureAllowedAfterRestart()))
            assertEquals(3, s.automatic.pending.size)
            assertEquals(future.id, takeNextConversationInput(s.human, s.automatic, false, false,
                f.futureAllowedAfterRestart())!!.orbisEventId)
            s.inbox.mark(future.id, "unknown", "synthetic_provider_failure")
            f.pause("automatic.json").pause(CONVERSATION, "unknown_tool_result")
            assertFalse(f.futureAllowedAfterRestart())
            assertNull(takeNextConversationInput(s.human, s.automatic, false, false, false))
            assertEquals("unknown", f.inbox().get(future.id)!!.state)
            s.previousEventIds.forEach { assertEquals("suppressed", f.inbox().get(it)!!.state) }
        }
    }

    @Test fun recoveryDoesNotClearAnotherConversationsHoldOrReceipt() {
        Fixture().use { f ->
            val s = Scenario(f)
            val otherConversation = "00000000-0000-4000-8000-000000000094"
            val otherAssistant = "00000000-0000-4000-8000-000000000095"
            val source = "native_sentinel.other-synthetic-rule"
            val binding = OrbisEventBinding(otherAssistant, otherConversation)
            s.inbox.bind(setOf(source), binding)
            val otherEvent = s.inbox.accept(OrbisIncomingEvent("other", source, "synthetic other event"), binding, 1_004L).first
            f.pause("automatic.json").pause(otherConversation, "unknown_tool_result")
            f.fresh().detachPreviousTurn(otherConversation, otherAssistant)
            f.pause("guard.json").pause(otherConversation, GUARD_REASON)
            assertTrue(s.recover())
            assertEquals(otherEvent, f.inbox().get(otherEvent.id))
            assertEquals("unknown_tool_result", f.pause("automatic.json").pauseReason(otherConversation))
            assertEquals(FreshHumanRecoveryStatus.DETACHED, f.fresh().status(otherConversation, otherAssistant))
            assertEquals(GUARD_REASON, f.pause("guard.json").pauseReason(otherConversation))
            assertTrue(f.futureAllowedAfterRestart())
        }
    }

    @Test fun oneShotSkipIsDurableAndDuplicateDoesNotDispatchButFreshEventCan() {
        Fixture().use { f ->
            val binding = OrbisEventBinding(ASSISTANT, CONVERSATION)
            val inbox = f.inbox()
            inbox.bind(setOf(SOURCE), binding)
            val input = OrbisIncomingEvent("one-shot-busy", SOURCE, "synthetic original text")
            val old = inbox.accept(input, binding, 1_000L).first
            var dispatched = 0
            assertNull(dispatchAutomaticWakeOnce("wake_reply_in_progress",
                skip = { inbox.mark(old.id, "skipped", it) }, dispatch = { ++dispatched }))
            val reopened = f.inbox()
            assertEquals(old.copy(state = "skipped", error = "wake_reply_in_progress"), reopened.get(old.id))
            val duplicate = reopened.accept(input, binding, 2_000L)
            assertTrue(duplicate.second)
            assertEquals("skipped", duplicate.first.state)
            reopened.mark(old.id, "generating")
            assertEquals("skipped", f.inbox().get(old.id)!!.state)
            assertEquals(0, dispatched)
            val next = reopened.accept(OrbisIncomingEvent("one-shot-fresh", SOURCE, "synthetic new event"), binding, 3_000L)
            assertFalse(next.second)
            assertEquals(1, dispatchAutomaticWakeOnce(null,
                skip = { fail("unexpected skip") }, dispatch = { ++dispatched }))
            assertEquals("skipped", reopened.get(old.id)!!.state)
        }
    }

    @Test fun oneShotSkipWriteFailureNeverDispatchesAndDoesNotInventCommittedReceipt() {
        Fixture().use { f ->
            val binding = OrbisEventBinding(ASSISTANT, CONVERSATION)
            val inbox = f.inbox()
            inbox.bind(setOf(SOURCE), binding)
            val old = inbox.accept(OrbisIncomingEvent("one-shot-failed", SOURCE, "synthetic original"), binding, 1_000L).first
            f.writes.clear()
            f.failWrite = "inbox.json" to 1
            var dispatched = 0
            assertTrue(runCatching {
                dispatchAutomaticWakeOnce("wake_gateway_unconfirmed",
                    skip = { inbox.mark(old.id, "skipped", it) }, dispatch = { ++dispatched })
            }.isFailure)
            assertEquals(0, dispatched)
            assertEquals(old, f.inbox().get(old.id))
        }
    }

    private companion object {
        const val CONVERSATION = "00000000-0000-4000-8000-000000000091"
        const val ASSISTANT = "00000000-0000-4000-8000-000000000092"
        const val SOURCE = "native_sentinel.synthetic-recovery-rule"
        const val GUARD_REASON = "future_automatic_wake_recovery"
        val FILES = setOf("human.json", "automatic.json", "fresh.json", "guard.json", "inbox.json")
    }
}
