package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.orbis.*
import org.junit.Assert.*
import org.junit.Test

class IndependentEventAdmissionTest {
    private val event = OrbisInboxEvent(id = "input", eventId = "new", source = "lc_sentinel", text = "test",
        wake = true, assistantId = "assistant", conversationId = "chat", receivedAt = 1,
        independentDelivery = true, attemptStarted = true, state = "queued")
    private fun permit(e: OrbisInboxEvent = event, input: String = "input", conversation: String = "chat",
        assistant: String = "assistant", binding: Boolean = true, master: Boolean = true) =
        permitsIndependentEvent(e, input, conversation, assistant, binding, master)

    @Test fun freshOnlyAndGatewayAndOldToolHoldsCannotVetoANewClaim() = runBlocking {
        val holds = listOf(
            AutomaticWakeReadiness(freshStatus = FreshHumanRecoveryStatus.ACTIVE),
            AutomaticWakeReadiness(freshStatus = FreshHumanRecoveryStatus.DETACHED),
            AutomaticWakeReadiness(gatewayRecoveryBlocked = true),
            AutomaticWakeReadiness(automaticHold = QueuePauseStatus.PAUSED, automaticHoldReason = "unknown_tool_result"),
            AutomaticWakeReadiness(recoveryGuard = QueuePauseStatus.PAUSED),
        )
        holds.forEach { old ->
            assertNotNull(old.restriction())
            var dispatched = 0
            withGatewayInputAdmission(null, awaitHistory = {},
                isBlocked = { old.restriction() != null && !permit() }) { dispatched++ }
            assertEquals(1, dispatched)
            assertNotNull("The old evidence was not erased", old.restriction())
        }
    }

    @Test fun authorityIsScopedToOneClaimAndOneOwner() {
        assertTrue(permit())
        assertFalse(permit(event.copy(independentDelivery = false)))
        assertFalse(permit(event.copy(attemptStarted = false)))
        assertFalse(permit(input = "old-input"))
        assertFalse(permit(conversation = "other-chat"))
        assertFalse(permit(assistant = "other-ai"))
        assertFalse(permit(binding = false))
        assertFalse(permit(master = false))
    }

    @Test fun terminalOrUnclaimedReceiptNeverAuthorizesAnotherAttempt() {
        for (state in listOf("accepted", "failed", "unknown", "skipped", "suppressed", "target_invalid", "replied"))
            assertFalse(state, permit(event.copy(state = state)))
        for (state in listOf("queued", "displayed", "generating", "pending_tool"))
            assertTrue(state, permit(event.copy(state = state)))
    }
}
