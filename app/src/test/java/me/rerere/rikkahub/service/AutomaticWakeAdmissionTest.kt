package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.orbis.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticWakeAdmissionTest {
    @Test fun humanPauseIsNotAnAutomaticHold() {
        assertNull(AutomaticWakeReadiness(queueStatus = QueuePauseStatus.PAUSED).restriction())
    }

    @Test fun everyPersistentGateHasAnActionableReason() {
        val cases = mapOf(
            AutomaticWakeReadiness(resetting = true) to "wake_recovery_in_progress",
            AutomaticWakeReadiness(localRecoveryBlocked = true) to "wake_local_recovery_unconfirmed",
            AutomaticWakeReadiness(gatewayRecoveryBlocked = true) to "wake_gateway_unconfirmed",
            AutomaticWakeReadiness(freshStatus = FreshHumanRecoveryStatus.DETACHED) to "wake_fresh_input_only",
            AutomaticWakeReadiness(freshStatus = FreshHumanRecoveryStatus.ACTIVE) to "wake_fresh_input_only",
            AutomaticWakeReadiness(freshStatus = FreshHumanRecoveryStatus.OWNER_CHANGED) to "wake_owner_changed",
            AutomaticWakeReadiness(freshStatus = FreshHumanRecoveryStatus.UNAVAILABLE) to "wake_storage_unavailable",
            AutomaticWakeReadiness(pauseStorageReady = false) to "wake_storage_unavailable",
            AutomaticWakeReadiness(queueStatus = QueuePauseStatus.UNAVAILABLE) to "wake_storage_unavailable",
            AutomaticWakeReadiness(recoveryGuard = QueuePauseStatus.PAUSED) to "wake_recovery_commit_unconfirmed",
            AutomaticWakeReadiness(automaticHold = QueuePauseStatus.UNAVAILABLE) to "wake_storage_unavailable",
            AutomaticWakeReadiness(automaticHold = QueuePauseStatus.PAUSED, automaticHoldReason = "unknown_tool_result") to "wake_unknown_tool_result",
            AutomaticWakeReadiness(automaticHold = QueuePauseStatus.PAUSED, automaticHoldReason = "event_receipt_not_saved") to "wake_receipt_unconfirmed",
        )
        cases.forEach { (input, expected) -> assertEquals(expected, input.restriction()) }
    }

    @Test fun transientBusyStatesAreNotSilentWaiting() {
        assertEquals("wake_reply_in_progress", automaticWakeAdmissionReason(null, true, false, false, false))
        assertEquals("wake_save_in_progress", automaticWakeAdmissionReason(null, false, true, false, false))
        assertEquals("wake_tool_pending", automaticWakeAdmissionReason(null, false, false, true, false))
        assertEquals("wake_human_input_first", automaticWakeAdmissionReason(null, false, false, false, true))
        assertNull(automaticWakeAdmissionReason(null, false, false, false, false))
    }

    @Test fun aSkippedEventCannotBecomePredecessorOfFreshOne() {
        val skipped = mutableListOf<String>()
        var calls = 0
        assertNull(dispatchAutomaticWakeOnce("wake_reply_in_progress", skipped::add) { ++calls })
        assertEquals(listOf("wake_reply_in_progress"), skipped)
        assertEquals(0, calls)
        assertEquals(1, dispatchAutomaticWakeOnce(null, skipped::add) { ++calls })
        assertEquals(1, calls)
        assertEquals(1, skipped.size)
    }

    @Test fun noDispatchIfSavingSkippedReceiptFails() {
        var dispatched = false
        assertTrue(runCatching {
            dispatchAutomaticWakeOnce("wake_storage_unavailable", { error("synthetic disk failure") }) { dispatched = true }
        }.isFailure)
        assertFalse(dispatched)
    }

    @Test fun dispatchExceptionDoesNotRetry() {
        var attempts = 0
        assertTrue(runCatching {
            dispatchAutomaticWakeOnce(null, { error("not expected") }) { attempts++; error("synthetic provider failure") }
        }.isFailure)
        assertEquals(1, attempts)
    }

    @Test fun skipIsDurableDeduplicatedAndTerminalButNextEventRemainsIndependent() {
        var disk: String? = null
        fun open() = OrbisEventInbox({ disk }, { disk = it })
        val binding = OrbisEventBinding("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
        val input = OrbisIncomingEvent("synthetic-1", "self_reminder", "synthetic original")
        val inbox = open().apply { bind(setOf(input.source), binding) }
        val first = inbox.accept(input, binding, 100).first
        dispatchAutomaticWakeOnce("wake_reply_in_progress", { inbox.mark(first.id, "skipped", it) }) { error("no dispatch") }
        val restarted = open()
        restarted.mark(first.id, "queued") // late old callback cannot revive it
        restarted.mark(first.id, "replied") // nor falsely claim a reply
        val replay = restarted.accept(input, binding, 200)
        assertTrue(replay.second)
        assertEquals("skipped", replay.first.state)
        assertEquals("synthetic original", replay.first.text)
        assertEquals("wake_reply_in_progress", replay.first.error)
        val fresh = restarted.accept(input.copy(event_id = "synthetic-2"), binding, 300)
        assertFalse(fresh.second)
        assertEquals("accepted", fresh.first.state)
    }

    @Test fun autoTransportRepairNeverAssumesAbsentOrOtherScopeIsSameDestination() {
        GatewayRecoveryScopeStatus.entries.forEach { scope ->
            assertEquals(scope == GatewayRecoveryScopeStatus.MATCH,
                mayRefreshAutomaticWakeTransport(scope, false, true, FreshHumanRecoveryStatus.DETACHED, null))
        }
        assertTrue(mayRefreshAutomaticWakeTransport(GatewayRecoveryScopeStatus.LEGACY, true, true, FreshHumanRecoveryStatus.ACTIVE, null))
        assertFalse(mayRefreshAutomaticWakeTransport(GatewayRecoveryScopeStatus.MATCH, true, false, FreshHumanRecoveryStatus.NONE, null))
        assertFalse(mayRefreshAutomaticWakeTransport(GatewayRecoveryScopeStatus.MATCH, true, true, FreshHumanRecoveryStatus.OWNER_CHANGED, null))
        assertFalse(mayRefreshAutomaticWakeTransport(GatewayRecoveryScopeStatus.MATCH, true, true, FreshHumanRecoveryStatus.UNAVAILABLE, null))
        assertFalse(mayRefreshAutomaticWakeTransport(GatewayRecoveryScopeStatus.MATCH, true, true, FreshHumanRecoveryStatus.NONE, "partial_snapshot_not_saved"))
    }
}
