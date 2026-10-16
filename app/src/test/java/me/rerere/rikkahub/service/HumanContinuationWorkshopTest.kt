package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import me.rerere.rikkahub.data.orbis.workshop.WorkshopAsset
import me.rerere.rikkahub.data.orbis.workshop.WorkshopJob
import me.rerere.rikkahub.data.orbis.workshop.WorkshopStatus
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class HumanContinuationWorkshopTest {
    private fun base() = Conversation(assistantId = Uuid.random(),
        messageNodes = listOf(UIMessage.user("original human").toMessageNode()))
    private fun submitted(c: Conversation) = WorkshopJob(requestId = "fresh-request",
        assistantId = c.assistantId.toString(), conversationId = c.id.toString(), byAi = true,
        modelId = Uuid.random().toString(), maker = "image maker", configHash = "config", prompt = "draw a flower",
        createdAt = 100)
    private fun completed(j: WorkshopJob) = j.copy(status = WorkshopStatus.COMPLETE,
        assets = listOf(WorkshopAsset("a".repeat(64) + ".png", "flower", "image/png", true)), delivered = true)
    private fun event(j: WorkshopJob) = OrbisInboxEvent(eventId = "result-${j.id}", source = "image_workshop.${j.id}",
        text = "result data", wake = true, assistantId = j.assistantId, conversationId = j.conversationId!!,
        receivedAt = 200, independentDelivery = true, workshopImages = j.assets.map { it.file })
    private fun input(e: OrbisInboxEvent) = UIMessage(id = Uuid.parse(e.id), role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(e.text), UIMessagePart.Image("content://test/original")),
        orbisEvent = OrbisEventMetadata(e.id, e.source, e.eventId, e.receivedAt))
    private fun HumanContinuationLane.reserve(e: OrbisInboxEvent, j: WorkshopJob) =
        reserveWorkshopReturn(e, j, targetMatches = true, deliveryAllowed = true, imageInputSupported = true)
    private fun Conversation.answer(text: String) = copy(messageNodes = messageNodes + UIMessage.assistant(text).toMessageNode())

    @Test fun `exact newly accepted job returns once with image to its original lane`() = runTest {
        val c = base(); val submitted = submitted(c); val done = completed(submitted); val e = event(done)
        val lane = HumanContinuationLane(c, backgroundScope, { true }, { fail(it.toString()) })
        assertTrue(lane.registerWorkshopReturn(0, submitted))
        val reservation = checkNotNull(lane.reserve(e, done))
        var calls = 0
        assertNotNull(lane.sendWorkshopReturn(reservation, input(e), true) { snapshot, _, publish ->
            calls++; assertEquals(c.assistantId, snapshot.assistantId)
            assertTrue(snapshot.currentMessages.last().parts.any { it is UIMessagePart.Image })
            publish(snapshot.answer("I see the result"))
        })
        runCurrent()
        assertEquals(1, calls); assertEquals("I see the result", lane.state.value.currentMessages.last().toText())
        assertNull(lane.reserve(e, done))
        assertNull(lane.sendWorkshopReturn(reservation, input(e), true) { _, _, _ -> fail("duplicate") })
        assertEquals(c.currentMessages.first(), lane.state.value.currentMessages.first())
    }

    @Test fun `a ledger job alone and a restarted lane do not grant a wake`() = runTest {
        val c = base(); val j = completed(submitted(c)); val e = event(j)
        val lane = HumanContinuationLane(c, backgroundScope, { true }, {})
        assertFalse(lane.matchesWorkshopReturn(e, j)); assertNull(lane.reserve(e, j))
        assertFalse(lane.registerWorkshopReturn(0, j))
    }

    @Test fun `old callback owner foreign target human retry and nonqueued jobs cannot register`() = runTest {
        val c = base(); val j = submitted(c); val lane = HumanContinuationLane(c, backgroundScope, { true }, {})
        lane.send(UIMessage.user("new turn"), answer = false) { _, _, _ -> }; runCurrent()
        assertFalse(lane.registerWorkshopReturn(0, j))
        listOf(j.copy(assistantId = Uuid.random().toString()), j.copy(conversationId = Uuid.random().toString()),
            j.copy(byAi = false), j.copy(notifyConversation = false), j.copy(status = WorkshopStatus.RUNNING),
            j.copy(delivered = true), completed(j).copy(status = WorkshopStatus.QUEUED)).forEach {
            assertFalse(lane.registerWorkshopReturn(1, it))
        }
        assertTrue(lane.registerWorkshopReturn(1, j)); assertFalse(lane.registerWorkshopReturn(1, j))
    }

    @Test fun `request identity immutable job fields and every event owner are verified`() = runTest {
        val c = base(); val j = submitted(c); val done = completed(j); val e = event(done)
        val lane = HumanContinuationLane(c, backgroundScope, { true }, {})
        lane.registerWorkshopReturn(0, j)
        listOf(done.copy(requestId = "old-request"), done.copy(createdAt = 1), done.copy(prompt = "different"),
            done.copy(configHash = "changed"), done.copy(modelId = Uuid.random().toString()), done.copy(byAi = false),
            done.copy(conversationId = Uuid.random().toString()), done.copy(assistantId = Uuid.random().toString()),
            done.copy(notifyConversation = false), done.copy(status = WorkshopStatus.RUNNING)).forEach {
            assertFalse(lane.matchesWorkshopReturn(e, it))
        }
        listOf(e.copy(source = "legacy_sentinel"), e.copy(eventId = "other-result"),
            e.copy(assistantId = Uuid.random().toString()), e.copy(conversationId = Uuid.random().toString()),
            e.copy(localImage = "a.jpg"), e.copy(independentDelivery = false),
            e.copy(workshopImages = listOf("b".repeat(64) + ".png"))).forEach {
            assertFalse(lane.matchesWorkshopReturn(it, done))
        }
        assertTrue(lane.matchesWorkshopReturn(e, done))
    }

    @Test fun `busy lane never reserves or claims and result may wait for the new turn`() = runTest {
        val c = base(); val j = submitted(c); val done = completed(j); val e = event(done)
        val finish = CompletableDeferred<Unit>(); val lane = HumanContinuationLane(c, backgroundScope, { true }, {})
        lane.send(UIMessage.user("make image")) { _, owner, _ ->
            assertTrue(lane.registerWorkshopReturn(owner, j)); finish.await()
        }; runCurrent()
        var claims = 0
        lane.reserve(e, done)?.let { claims++ }
        assertEquals(0, claims)
        finish.complete(Unit); runCurrent()
        assertNotNull(lane.reserve(e, done))
    }

    @Test fun `pause wrong target no vision and reset all block before a durable claim`() = runTest {
        val c = base(); val j = submitted(c); val done = completed(j); val e = event(done)
        val lane = HumanContinuationLane(c, backgroundScope, { true }, {}); lane.registerWorkshopReturn(0, j)
        assertNull(lane.reserveWorkshopReturn(e, done, false, true, true))
        assertNull(lane.reserveWorkshopReturn(e, done, true, false, true))
        assertNull(lane.reserveWorkshopReturn(e, done, true, true, false))
        assertNull(lane.reserveWorkshopReturn(e, done, true, true, true, resetting = true))
        assertNull(lane.reserve(e.copy(attemptStarted = true), done))
        assertNull(lane.reserve(e.copy(state = "suppressed"), done))
        assertNotNull(lane.reserve(e, done))
    }

    @Test fun `pending approval cannot be preempted by a workshop result`() = runTest {
        val c = base(); val j = submitted(c); val done = completed(j); val e = event(done)
        val lane = HumanContinuationLane(c, backgroundScope, { true }, {})
        lane.registerWorkshopReturn(0, j)
        lane.send(UIMessage.user("write a file")) { current, _, publish ->
            publish(current.copy(messageNodes = current.messageNodes + UIMessage(role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Tool(toolCallId = "new-write", toolName = "write", input = "{}"))).toMessageNode()))
        }; runCurrent()
        assertNull(lane.reserve(e, done))
        lane.send(UIMessage.user("never mind"), answer = false) { _, _, _ -> }; runCurrent()
        assertNotNull(lane.reserve(e, done))
    }

    @Test fun `blocked disk claim and persistence cannot delay a fresh human send`() = runTest {
        val c = base(); val j = submitted(c); val done = completed(j); val e = event(done)
        val disk = CompletableDeferred<Boolean>(); val claim = CompletableDeferred<Boolean>()
        val lane = HumanContinuationLane(c, backgroundScope, { disk.await() }, {})
        lane.registerWorkshopReturn(0, j)
        val reservation = checkNotNull(lane.reserve(e, done)); var wakes = 0
        backgroundScope.launch {
            val confirmed = claim.await()
            assertNull(lane.sendWorkshopReturn(reservation, input(e), confirmed) { _, _, _ -> wakes++ })
        }; runCurrent()
        lane.send(UIMessage.user("human goes first")) { current, _, publish -> publish(current.answer("yes")) }
        runCurrent()
        assertEquals("yes", lane.state.value.currentMessages.last().toText()); assertNull(lane.job.value)
        claim.complete(true); runCurrent(); assertEquals(0, wakes)
        assertFalse(lane.state.value.currentMessages.any { it.orbisEvent?.recordId == e.id })
        disk.complete(true); runCurrent()
    }

    @Test fun `failed claim and changed input provenance never dispatch`() = runTest {
        val c = base(); val j = submitted(c); val done = completed(j); val e = event(done)
        val lane = HumanContinuationLane(c, backgroundScope, { true }, {}); lane.registerWorkshopReturn(0, j)
        val first = checkNotNull(lane.reserve(e, done))
        assertNull(lane.sendWorkshopReturn(first, input(e), false) { _, _, _ -> fail("unclaimed") })
        val second = checkNotNull(lane.reserve(e, done))
        assertNull(lane.sendWorkshopReturn(second, input(e).copy(orbisEvent = null), true) { _, _, _ -> fail("not event") })
        assertEquals(c, lane.state.value)
    }

    @Test fun `late automatic producer cannot overwrite a successor human turn`() = runTest {
        val c = base(); val j = submitted(c); val done = completed(j); val e = event(done)
        val lane = HumanContinuationLane(c, backgroundScope, { false }, {}); lane.registerWorkshopReturn(0, j)
        val late = CompletableDeferred<Unit>(); val reserved = checkNotNull(lane.reserve(e, done))
        lane.sendWorkshopReturn(reserved, input(e), true) { current, _, publish ->
            withContext(NonCancellable) { late.await(); publish(current.answer("late image response")) }
        }; runCurrent()
        lane.send(UIMessage.user("new human")) { current, _, publish -> publish(current.answer("fresh answer")) }
        runCurrent(); late.complete(Unit); runCurrent()
        assertEquals("fresh answer", lane.state.value.currentMessages.last().toText())
        assertFalse(lane.state.value.currentMessages.any { it.toText() == "late image response" })
    }

    @Test fun `failed workshop result may report failure but cannot recharge from an old job`() = runTest {
        val c = base(); val j = submitted(c); val failed = j.copy(status = WorkshopStatus.FAILED, error = "network")
        val e = event(failed); val lane = HumanContinuationLane(c, backgroundScope, { true }, {})
        lane.registerWorkshopReturn(0, j)
        assertNotNull(lane.reserveWorkshopReturn(e, failed, true, true, imageInputSupported = false))
    }
}
