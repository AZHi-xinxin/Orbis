package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
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
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class HumanContinuationSentinelTest {
    private fun base() = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
    private fun event(c: Conversation) = OrbisInboxEvent(eventId = "fresh", source = "native_sentinel.test",
        text = "synthetic reminder", wake = true, assistantId = c.assistantId.toString(),
        conversationId = c.id.toString(), receivedAt = 100, independentDelivery = true)
    private fun input(e: OrbisInboxEvent) = UIMessage(id = Uuid.parse(e.id), role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(e.text)),
        orbisEvent = OrbisEventMetadata(e.id, e.source, e.eventId, e.receivedAt))
    private fun HumanContinuationLane.reserve(e: OrbisInboxEvent) = reserveSentinel(e, true, true)
    private fun Conversation.answer(text: String) = copy(messageNodes = messageNodes + UIMessage.assistant(text).toMessageNode())

    @Test fun freshNotificationWakesIdleContinuationExactlyOnce() = runTest {
        val c = base(); val e = event(c)
        val lane = HumanContinuationLane(c, backgroundScope, { true }, { fail(it.toString()) })
        lane.send(UIMessage.user("continue"), false) { _, _, _ -> }; runCurrent()
        val reservation = checkNotNull(lane.reserve(e)); var wakes = 0
        assertNotNull(lane.sendSentinel(reservation, input(e), true) { snapshot, _, publish ->
            wakes++; publish(snapshot.answer("reminder received"))
        }); runCurrent()
        assertEquals(1, wakes)
        assertEquals("reminder received", lane.state.value.currentMessages.last().toText())
        assertNull(lane.reserve(e))
        assertNull(lane.sendSentinel(reservation, input(e), true) { _, _, _ -> fail("replay") })
    }

    @Test fun failedOldAttemptsForeignTargetsAndWorkshopCannotEnterSentinelLane() = runTest {
        val c = base(); val e = event(c); val lane = HumanContinuationLane(c, backgroundScope, { true }, {})
        listOf(e.copy(attemptStarted = true), e.copy(state = "unknown"), e.copy(state = "failed"),
            e.copy(independentDelivery = false), e.copy(assistantId = Uuid.random().toString()),
            e.copy(conversationId = Uuid.random().toString()), e.copy(source = "image_workshop.${Uuid.random()}"),
            e.copy(workshopImages = listOf("unexpected.png"))).forEach { assertNull(lane.reserve(it)) }
        assertNull(lane.reserveSentinel(e, false, true))
        assertNull(lane.reserveSentinel(e, true, false))
        assertNull(lane.reserveSentinel(e, true, true, true))
        assertNotNull(lane.reserve(e))
    }

    @Test fun humanInputWinsWhileDurableClaimOrSaveIsSlow() = runTest {
        val c = base(); val e = event(c); val disk = CompletableDeferred<Boolean>()
        val lane = HumanContinuationLane(c, backgroundScope, { disk.await() }, {})
        val reservation = checkNotNull(lane.reserve(e))
        lane.send(UIMessage.user("human next")) { snapshot, _, publish -> publish(snapshot.answer("yes")) }
        runCurrent()
        assertEquals("yes", lane.state.value.currentMessages.last().toText())
        assertNull(lane.sendSentinel(reservation, input(e), true) { _, _, _ -> fail("human preempted") })
        disk.complete(false); runCurrent()
        assertNotNull(lane.reserve(e))
    }

    @Test fun busyHumanAndPendingApprovalsWaitWithoutClaim() = runTest {
        val c = base(); val e = event(c); val done = CompletableDeferred<Unit>()
        val lane = HumanContinuationLane(c, backgroundScope, { true }, {})
        lane.send(UIMessage.user("work")) { snapshot, _, publish ->
            done.await()
            publish(snapshot.copy(messageNodes = snapshot.messageNodes + UIMessage(role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Tool(toolCallId = "pending", toolName = "write", input = "{}"))).toMessageNode()))
        }; runCurrent(); assertNull(lane.reserve(e))
        done.complete(Unit); runCurrent(); assertNull(lane.reserve(e))
        lane.stop(); runCurrent(); assertNotNull(lane.reserve(e))
    }

    @Test fun lateSentinelCannotOverwriteNewHumanReplyEvenWhenPersistenceFails() = runTest {
        val c = base(); val e = event(c); val late = CompletableDeferred<Unit>()
        val lane = HumanContinuationLane(c, backgroundScope, { false }, {})
        lane.sendSentinel(checkNotNull(lane.reserve(e)), input(e), true) { snapshot, _, publish ->
            withContext(NonCancellable) { late.await(); publish(snapshot.answer("late")) }
        }; runCurrent()
        lane.send(UIMessage.user("hello")) { snapshot, _, publish -> publish(snapshot.answer("human reply")) }
        runCurrent(); late.complete(Unit); runCurrent()
        assertEquals("human reply", lane.state.value.currentMessages.last().toText())
        assertFalse(lane.state.value.currentMessages.any { it.toText() == "late" })
    }

    @Test fun failedClaimOrMismatchedMetadataNeverGenerates() = runTest {
        val c = base(); val e = event(c); val lane = HumanContinuationLane(c, backgroundScope, { true }, {})
        assertNull(lane.sendSentinel(checkNotNull(lane.reserve(e)), input(e), false) { _, _, _ -> fail("unclaimed") })
        assertNull(lane.sendSentinel(checkNotNull(lane.reserve(e)), input(e).copy(orbisEvent = null), true) { _, _, _ -> fail("wrong input") })
        assertEquals(c, lane.state.value)
    }
}
