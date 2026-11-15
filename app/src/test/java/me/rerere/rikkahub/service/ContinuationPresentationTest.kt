package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class ContinuationPresentationTest {
    @Test fun `stopped ordinary task cannot reappear after continuation saves and hands back`() = runTest {
        val original = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val session = ConversationSession(original.id, original, backgroundScope, {})
        val cleanup = CompletableDeferred<Unit>()
        val old = backgroundScope.launch(start = CoroutineStart.LAZY) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        var displayed: Job? = null
        backgroundScope.launch {
            session.humanContinuation.flatMapLatest { it?.job ?: session.generationJob }.collect { displayed = it }
        }
        try {
            session.setJob(old); runCurrent(); assertSame(old, displayed)
            val lane = HumanContinuationLane(original, backgroundScope, { true }, { throw it })
            session.humanContinuation.value = lane
            session.messageQueue.enqueue(listOf(me.rerere.ai.ui.UIMessagePart.Text("old queued input")))
            session.messageQueue.holdAllInputsForFreshRecovery()
            session.cancelJobs(); lane.stop(); runCurrent()
            assertNull(displayed)
            assertTrue(lane.retireForHistoryEdit(lane.state.value))
            session.humanContinuation.value = null; runCurrent()
            assertTrue(old.isCancelled); assertFalse(old.isCompleted)
            assertTrue(session.hasUnfinishedJobs())
            assertSame("Cleanup ownership must stay available internally", old, session.getJob())
            assertNull("Saved stop must not show the old task as a new reply", displayed)
            assertNull("Old queued input must stay held", session.messageQueue.takeNext())
            assertEquals(1, session.messageQueue.state.value.messages.size)
        } finally { cleanup.complete(Unit); runCurrent(); session.cleanup() }
    }

    @Test fun `old cancelled cleanup completing cannot hide a fresh displayed reply`() = runTest {
        val original = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val session = ConversationSession(original.id, original, backgroundScope, {})
        val cleanup = CompletableDeferred<Unit>()
        val freshDone = CompletableDeferred<Unit>()
        val old = backgroundScope.launch(start = CoroutineStart.LAZY) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        try {
            session.setJob(old); runCurrent(); session.cancelJobs()
            assertNull(session.generationJob.value)
            val fresh = backgroundScope.launch(start = CoroutineStart.LAZY) { freshDone.await() }
            session.setJob(fresh); runCurrent()
            assertSame(fresh, session.generationJob.value)
            cleanup.complete(Unit); runCurrent()
            assertSame(fresh, session.generationJob.value)
            freshDone.complete(Unit); runCurrent()
            assertNull(session.generationJob.value)
        } finally { cleanup.complete(Unit); freshDone.complete(Unit); runCurrent(); session.cleanup() }
    }

    @Test fun `phone web snapshot and stream share live continuation while old persistence waits`() = runTest {
        val original = Conversation(assistantId = Uuid.random(), messageNodes = listOf(UIMessage.user("old").toMessageNode()))
        val session = ConversationSession(original.id, original, backgroundScope, {})
        val stuck = CompletableDeferred<Boolean>()
        val lane = HumanContinuationLane(original, backgroundScope, { stuck.await() }, { throw it },
            checkpoint = { true })
        runCurrent()
        session.humanContinuation.value = lane
        lane.send(UIMessage.user("new")) { c, _, p ->
            p(c.copy(messageNodes = c.messageNodes + UIMessage.assistant("new reply").toMessageNode()))
        }
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals("new reply", session.displayedSnapshot().currentMessages.last().toText())
        assertEquals(session.displayedSnapshot(), session.displayedState.value)
        assertEquals("old", session.state.value.currentMessages.last().toText())
        assertNull(lane.persistenceWarning.value)
        // A late old snapshot must not roll back either presentation surface.
        session.state.value = original.copy(title = "old callback")
        runCurrent()
        assertEquals("new reply", session.displayedState.value.currentMessages.last().toText())
        stuck.complete(true); runCurrent()
        session.state.value = lane.state.value
        session.humanContinuation.value = null
        runCurrent()
        assertEquals("new reply", session.displayedState.value.currentMessages.last().toText())
    }

    @Test fun `warning only when both durable paths fail and disappears when either saves`() = runTest {
        var checkpointWorks = false
        val original = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val failures = mutableListOf<Exception>()
        val lane = HumanContinuationLane(original, backgroundScope, { throw java.io.IOException("disk full") }, { failures += it },
            checkpoint = { checkpointWorks })
        lane.send(UIMessage.user("first"), answer = false) { _, _, _ -> }
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertNotNull(lane.persistenceWarning.value)
        assertTrue(failures.isNotEmpty())
        assertTrue(failures.all { it.message == "disk full" })
        checkpointWorks = true
        lane.requestSave()
        runCurrent()
        assertNull(lane.persistenceWarning.value)
    }
}
