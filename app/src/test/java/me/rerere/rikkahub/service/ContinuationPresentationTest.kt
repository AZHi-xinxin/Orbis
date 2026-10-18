package me.rerere.rikkahub.service

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
        val lane = HumanContinuationLane(original, backgroundScope, { false }, { throw it },
            checkpoint = { checkpointWorks })
        lane.send(UIMessage.user("first"), answer = false) { _, _, _ -> }
        runCurrent(); advanceTimeBy(251); runCurrent()
        assertNotNull(lane.persistenceWarning.value)
        checkpointWorks = true
        lane.requestSave()
        runCurrent()
        assertNull(lane.persistenceWarning.value)
    }
}
