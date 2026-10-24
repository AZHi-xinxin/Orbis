package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationInjectionEdit
import me.rerere.rikkahub.data.model.ConversationInjectionSelection
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationInjectionSelectionTest {
    @Test fun `picker membership changes do not revert unrelated changes or append stale messages`() {
        val a = Uuid.random(); val b = Uuid.random(); val c = Uuid.random()
        val before = Conversation(assistantId = Uuid.random(), title = "before", messageNodes = emptyList(), lorebookIds = setOf(a))
        val edited = before.copy(lorebookIds = setOf(b))
        val live = before.copy(title = "new title", lorebookIds = setOf(a, c),
            messageNodes = listOf(UIMessage.user("new input").toMessageNode()))
        val selection = ConversationInjectionEdit.between(before, edited).applyTo(live)
        val after = selection.applyTo(live)
        assertEquals(setOf(b, c), after.lorebookIds)
        assertEquals(live.messageNodes, after.messageNodes)
        assertEquals(live.title, after.title)
        assertEquals(selection, ConversationInjectionEdit.between(before, edited).applyTo(after))
    }

    @Test fun `selection refuses a different owner or window`() {
        val before = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val edit = ConversationInjectionEdit.between(before, before.copy(lorebookIds = setOf(Uuid.random())))
        assertTrue(runCatching { edit.applyTo(before.copy(assistantId = Uuid.random())) }.isFailure)
        assertTrue(runCatching { edit.applyTo(before.copy(id = Uuid.random())) }.isFailure)
    }

    @Test fun `committed picker selection survives old streaming snapshots in independent continuation`() = runTest {
        val before = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        var checkpoint: Conversation? = null
        val held = CompletableDeferred<Unit>()
        var owner = -1L
        var old: Conversation? = null
        val lane = HumanContinuationLane(before, backgroundScope, { false }, { throw it },
            checkpoint = { checkpoint = it; true })
        lane.send(UIMessage.user("input")) { snapshot, turn, _ ->
            old = snapshot; owner = turn; held.await()
        }
        runCurrent()
        val selection = ConversationInjectionSelection(setOf(Uuid.random()), setOf(Uuid.random()))
        lane.acceptInjectionSelection(selection)
        lane.publish(owner, old!!.copy(messageNodes = old!!.messageNodes + UIMessage.assistant("later reply").toMessageNode()))
        lane.requestSave(); runCurrent(); advanceTimeBy(251); runCurrent()
        assertEquals(selection, ConversationInjectionSelection.from(lane.state.value))
        assertEquals("later reply", lane.state.value.currentMessages.last().toText())
        assertEquals(selection, ConversationInjectionSelection.from(checkNotNull(checkpoint)))
        assertTrue(old!!.lorebookIds.isEmpty())
        held.complete(Unit); runCurrent()
    }
}
