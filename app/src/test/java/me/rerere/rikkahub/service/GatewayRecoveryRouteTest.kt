package me.rerere.rikkahub.service

import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.FreshHumanInputRecoveryStore
import me.rerere.rikkahub.data.orbis.FreshHumanRecoveryStatus
import me.rerere.rikkahub.data.orbis.GatewayRecoveryScopeStore
import me.rerere.rikkahub.data.orbis.GatewayRecoveryScopeStatus
import me.rerere.rikkahub.data.orbis.OrbisQueuePauseStore
import me.rerere.rikkahub.data.orbis.QueuePauseStatus
import org.junit.Assert.*
import org.junit.Test

/** Production route selection + lane recovery + real stores/queue, not an Android service test. */
class GatewayRecoveryRouteTest {
    private val conversation = "11111111-1111-4111-8111-111111111111"
    private val assistant = "22222222-2222-4222-8222-222222222222"
    private val fingerprint = "a".repeat(64)

    private class Disk {
        var bytes: String? = null
        val store = OrbisQueuePauseStore({ bytes }, { bytes = it })
    }

    @Test fun `durable transport hold always selects current lane even without fresh human restriction`() {
        assertTrue(shouldUseGatewayThreadRecovery(FreshHumanRecoveryStatus.NONE,
            QueuePauseStatus.PAUSED, null))
        assertTrue(shouldUseGatewayThreadRecovery(FreshHumanRecoveryStatus.NONE,
            QueuePauseStatus.UNPAUSED, "gateway_terminal_unconfirmed"))
        assertTrue(shouldUseGatewayThreadRecovery(FreshHumanRecoveryStatus.ACTIVE,
            QueuePauseStatus.UNPAUSED, null))
    }

    @Test fun `ordinary local and unrelated automatic pauses do not acquire gateway control authority`() {
        for (reason in listOf(null, "generation_or_human_pause", "unknown_tool_result")) {
            assertFalse(shouldUseGatewayThreadRecovery(FreshHumanRecoveryStatus.NONE,
                QueuePauseStatus.UNPAUSED, reason))
        }
        assertFalse(shouldUseGatewayThreadRecovery(FreshHumanRecoveryStatus.NONE,
            QueuePauseStatus.UNAVAILABLE, "gateway_terminal_unconfirmed"))
        assertFalse(shouldUseGatewayThreadRecovery(FreshHumanRecoveryStatus.OWNER_CHANGED,
            QueuePauseStatus.UNPAUSED, null))
    }

    @Test fun `same process second failure with retained empty owner can recover current idle without replay`() = runTest {
        val gateway = Disk().store
        val scopes = GatewayRecoveryScopeStore(Disk().store)
        val fresh = FreshHumanInputRecoveryStore(Disk().store)
        val automatic = Disk().store
        val queue = MessageQueue()
        var threadProbes = 0
        var oldRequestProbes = 0
        var toolReplays = 0
        // This retained record is deliberately non-null but has NO usable old HTTP nonce.
        // It used to prevent ChatService from ever entering current-thread recovery.
        var retainedOwner: List<String>? = null

        repeat(2) { attempt ->
            gateway.pause(conversation, "gateway_terminal_unconfirmed")
            scopes.record(conversation, fingerprint)
            queue.pause()
            queue.enqueue(listOf(UIMessagePart.Text("old visual $attempt")),
                voiceCallId = conversation, voiceCallKind = "visual")
            retainedOwner = emptyList()
            assertNotNull(retainedOwner)
            assertEquals(FreshHumanRecoveryStatus.NONE, fresh.status(conversation, assistant))
            assertEquals(GatewayRecoveryScopeStatus.MATCH, scopes.status(conversation, fingerprint))

            val currentLaneRoute = shouldUseGatewayThreadRecovery(fresh.status(conversation, assistant),
                gateway.status(conversation), automatic.pauseReason(conversation))
            assertTrue(currentLaneRoute)
            val remote = recoverCurrentGatewayThread<String>(
                { threadProbes++; ThreadRecoveryProbe(ThreadRecoveryState.IDLE) },
                { oldRequestProbes++; error("idle does not need the missing historical nonce") },
                { toolReplays++; error("idle does not authorize tool replay or stop") }, { true })
            assertEquals(ThreadRecoveryState.IDLE, remote)
            assertTrue(commitRecoveredGatewayLane({ true },
                { gateway.pause(conversation, "gateway_terminal_unconfirmed") },
                { queue.holdAllInputsForFreshRecovery() },
                { automatic.resumeIfReason(conversation, "gateway_terminal_unconfirmed") },
                { scopes.clearAfterConfirmedIdle(conversation, fingerprint) },
                { fresh.clearAfterConfirmedIdle(conversation, assistant) },
                { queue.resume() }, { gateway.resume(conversation) }, { retainedOwner = null }))
            assertNull(retainedOwner)
            assertFalse(queue.state.value.paused)
            assertNull(queue.takeNext()) // The old camera input remains held, never replayed.
            val newText = listOf(UIMessagePart.Text("explicit new human input $attempt"))
            queue.enqueue(newText)
            assertEquals(newText, queue.takeNext()!!.parts)
        }
        assertEquals(2, threadProbes)
        assertEquals(0, oldRequestProbes)
        assertEquals(0, toolReplays)
        assertEquals(2, queue.state.value.messages.size)
        assertTrue(queue.state.value.messages.all { it.recoveryHeldReason != null })
    }

    @Test fun `same process stale request uses actual delivered wait then a new idle proof`() = runTest {
        val gateway = Disk().store
        gateway.pause(conversation, "gateway_terminal_unconfirmed")
        val oldLocalNonce = "unusable-old-request"
        val serverNonce = "actual-current-request"
        var probes = 0
        val stopped = mutableListOf<String>()
        assertTrue(shouldUseGatewayThreadRecovery(FreshHumanRecoveryStatus.NONE,
            gateway.status(conversation), null))
        val state = recoverCurrentGatewayThread(
            { if (probes++ == 0) ThreadRecoveryProbe(ThreadRecoveryState.OWNED_BUSY, serverNonce)
                else ThreadRecoveryProbe(ThreadRecoveryState.IDLE) },
            { assertNotEquals(oldLocalNonce, it); assertEquals(serverNonce, it); GatewayStopProbe.CAN_STOP },
            { stopped += it; true }, { true })
        assertEquals(ThreadRecoveryState.IDLE, state)
        assertEquals(listOf(serverNonce), stopped)
        assertEquals(2, probes)
        // Merely selecting/probing never clears protection; durable commit is still owed.
        assertEquals(QueuePauseStatus.PAUSED, gateway.status(conversation))
    }

    @Test fun `captured owner mismatch rejects before probing even if lane route is selected`() = runTest {
        assertTrue(shouldUseGatewayThreadRecovery(FreshHumanRecoveryStatus.NONE,
            QueuePauseStatus.PAUSED, null))
        assertEquals(ThreadRecoveryState.OWNER_CHANGED, recoverCurrentGatewayThread<String>(
            { error("different captured provider cannot be probed") },
            { error("no exact status") }, { error("no stop") }, { false }))
    }
}
