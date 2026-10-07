package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.orbis.FreshHumanInputRecoveryStore
import me.rerere.rikkahub.data.orbis.FreshHumanRecoveryStatus
import me.rerere.rikkahub.data.orbis.OrbisQueuePauseStore
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Exercises the production launch boundary with real queue/store/permits, no Android or network. */
class GatewayInputAdmissionIntegrationTest {
    private val conversation = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val assistant = Uuid.parse("22222222-2222-4222-8222-222222222222")

    private class Storage {
        var bytes: String? = null
        val store = FreshHumanInputRecoveryStore(OrbisQueuePauseStore({ bytes }, { bytes = it }))
    }

    @Test fun `selected fresh input crosses the actual launch boundary without releasing old hold`() = runTest {
        val disk = Storage()
        disk.store.authorize(conversation.toString(), assistant.toString())
        val gatewayFacts = OrbisQueuePauseStore({ """{"version":1,"legacyMigrationDone":true,"pauses":{"$conversation":"gateway_terminal_unconfirmed"}}""" },
            { error("must not clear gateway hold") })
        val gate = FreshHumanInputGate(assistant)
        val queue = MessageQueue(initiallyPaused = true)
        queue.enqueue(listOf(UIMessagePart.Text("old text must not send")))
        queue.holdAllInputsForFreshRecovery()
        queue.resume()
        val id = Uuid.random()
        queue.enqueue(listOf(UIMessagePart.Text("explicitly new text")), id = id, freshHumanInputPermit = gate.issue(id))
        val selected = queue.takeNext { gate.permits(it, assistant) }!!
        var bodies = 0
        withGatewayInputAdmission(selected, awaitHistory = {}, isBlocked = { input ->
            disk.store.status(conversation.toString(), assistant.toString()) != FreshHumanRecoveryStatus.ACTIVE ||
                input == null || !gate.permits(input, assistant)
        }) { bodies++ }
        assertEquals(1, bodies)
        assertTrue(gatewayFacts.isPaused(conversation.toString()))
        assertNotNull(queue.state.value.messages.single().recoveryHeldReason)
        assertNull(queue.takeNext { gate.permits(it, assistant) })
    }

    @Test fun `recovery while launch waits invalidates a previously selected old permission`() = runTest {
        var gate = FreshHumanInputGate(assistant)
        val id = Uuid.random()
        val queue = MessageQueue()
        queue.enqueue(listOf(UIMessagePart.Text("accepted before second recovery")), id = id,
            freshHumanInputPermit = gate.issue(id))
        val selected = queue.takeNext { gate.permits(it, assistant) }!!
        val barrier = CompletableDeferred<Unit>()
        var bodies = 0
        val pending = async {
            withGatewayInputAdmission(selected, { barrier.await() }, { it == null || !gate.permits(it, assistant) }) { bodies++ }
        }
        runCurrent()
        gate = FreshHumanInputGate(assistant)
        barrier.complete(Unit)
        try { pending.await(); fail("new recovery epoch must reject selected old input") }
        catch (_: CancellationException) { }
        assertEquals(0, bodies)
    }

    @Test fun `assistant change during history wait blocks dispatch before entering generation body`() = runTest {
        val gate = FreshHumanInputGate(assistant)
        var currentAssistant = assistant
        val id = Uuid.random()
        val input = QueuedMessage(id = id, parts = listOf(UIMessagePart.Text("new text")), freshHumanInputPermit = gate.issue(id))
        var bodies = 0
        try {
            withGatewayInputAdmission(input, { currentAssistant = Uuid.random() },
                { it == null || !gate.permits(it, currentAssistant) }) { bodies++ }
            fail("ownership change must block")
        } catch (_: CancellationException) { }
        assertEquals(0, bodies)
    }

    @Test fun `ordinary old synthetic and missing input cannot borrow the new-input launch route`() = runTest {
        val gate = FreshHumanInputGate(assistant)
        val text = listOf(UIMessagePart.Text("preserved input"))
        val id = Uuid.random()
        val fresh = QueuedMessage(id = id, parts = text, freshHumanInputPermit = gate.issue(id))
        val denied = listOf(null, QueuedMessage(parts = text), fresh.copy(orbisEventId = "automatic"),
            fresh.copy(voiceCallId = "call", voiceCallKind = "opening"),
            fresh.copy(voiceCallId = "call", voiceCallKind = "visual"), fresh.copy(recoveryHeldReason = "old"))
        var bodies = 0
        for (input in denied) {
            try {
                withGatewayInputAdmission(input, {}, { it == null || !gate.permits(it, assistant) }) { bodies++ }
                fail("input must not cross actual launch boundary")
            } catch (_: CancellationException) { }
        }
        assertEquals(0, bodies)
    }

    @Test fun `successful fresh launch does not permit reusing its completed input`() = runTest {
        val gate = FreshHumanInputGate(assistant)
        val id = Uuid.random()
        val input = QueuedMessage(id = id, parts = listOf(UIMessagePart.Text("new")), freshHumanInputPermit = gate.issue(id))
        var bodies = 0
        withGatewayInputAdmission(input, {}, { it == null || !gate.permits(it, assistant) }) { bodies++ }
        input.freshHumanInputPermit!!.completed = true
        try {
            withGatewayInputAdmission(input, {}, { it == null || !gate.permits(it, assistant) }) { bodies++ }
            fail("completed input cannot replay")
        } catch (_: CancellationException) { }
        assertEquals(1, bodies)
    }

    @Test fun `conversation model and provider changes after selection block the actual launch boundary`() = runTest {
        val model = Model(modelId = "test-model")
        val provider = ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://unused.invalid")
        val scope = FreshHumanInputScope(conversation, model, listOf(provider))
        val gate = FreshHumanInputGate(assistant, scope)
        val id = Uuid.random()
        val input = QueuedMessage(id = id, parts = listOf(UIMessagePart.Text("explicit new text")),
            freshHumanInputPermit = gate.issue(id))
        var bodies = 0
        for (changed in listOf(scope.copy(conversationId = Uuid.random()),
            scope.copy(model = model.copy(modelId = "changed")),
            scope.copy(providers = listOf(provider.copy(baseUrl = "https://other.invalid"))))) {
            var current = scope
            try {
                withGatewayInputAdmission(input, { current = changed },
                    { it == null || !gate.permits(it, assistant, current) }) { bodies++ }
                fail("routing change must invalidate the selected input")
            } catch (_: CancellationException) { }
        }
        assertEquals(0, bodies)
        assertFalse(gate.permits(input, assistant)) // Production scope cannot be omitted to bypass binding.
        withGatewayInputAdmission(input, {}, { it == null || !gate.permits(it, assistant, scope) }) { bodies++ }
        assertEquals(1, bodies)
    }

    @Test fun `malformed held call metadata cannot become ordinary editable input`() {
        val queue = MessageQueue()
        queue.enqueue(listOf(UIMessagePart.Text("retained")), voiceCallId = "call-without-kind")
        queue.enqueue(listOf(UIMessagePart.Text("retained")), voiceCallKind = "turn")
        queue.holdAllInputsForFreshRecovery()
        for (input in queue.state.value.messages) {
            assertNull(queue.beginEdit(input.id))
            assertNull(queue.finishEdit(input.id, listOf(UIMessagePart.Text("must not convert"))))
        }
        assertEquals(2, queue.state.value.messages.size)
        assertTrue(queue.state.value.messages.all { it.recoveryHeldReason != null })
    }

    @Test fun `a successful new human answer does not authorize derived title or suggestion calls`() = runTest {
        val disk = Storage()
        assertTrue(freshHumanModeAllowsDerivedRequests(
            disk.store.status(conversation.toString(), assistant.toString())))
        disk.store.authorize(conversation.toString(), assistant.toString())
        val gate = FreshHumanInputGate(assistant)
        val id = Uuid.random()
        val input = QueuedMessage(id = id, parts = listOf(UIMessagePart.Text("new human input")),
            freshHumanInputPermit = gate.issue(id))
        var humanCalls = 0
        var derivedCalls = 0
        withGatewayInputAdmission(input, {}, { it == null || !gate.permits(it, assistant) }) { humanCalls++ }
        repeat(2) {
            if (freshHumanModeAllowsDerivedRequests(disk.store.status(conversation.toString(), assistant.toString())))
                derivedCalls++
        }
        assertEquals(1, humanCalls)
        assertEquals(0, derivedCalls)
        assertFalse(freshHumanModeAllowsDerivedRequests(FreshHumanRecoveryStatus.OWNER_CHANGED))
        assertFalse(freshHumanModeAllowsDerivedRequests(FreshHumanRecoveryStatus.UNAVAILABLE))
    }
}
