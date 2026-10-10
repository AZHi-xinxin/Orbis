package me.rerere.rikkahub.data.orbis

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class IndependentSentinelDeliveryTest {
    private val binding = OrbisEventBinding("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
    private class Disk {
        var text: String? = null
        var fail = false
        fun open() = OrbisEventInbox({ text }, { check(!fail) { "disk_failure" }; text = it })
    }
    private fun setup(disk: Disk) = disk.open().also { it.bind(setOf("lc_sentinel"), binding) }
    private fun accept(inbox: OrbisEventInbox, id: String = "new") = inbox.accept(
        OrbisIncomingEvent(id, "lc_sentinel", "synthetic $id"), binding, 123L, independentDelivery = true).first

    @Test fun deliveredBeforeModelClaimAndSurvivesRestart() {
        val disk = Disk(); val inbox = setup(disk); val event = accept(inbox)
        assertTrue(event.isConversationNotification(binding.assistantId, binding.conversationId))
        assertFalse(event.attemptStarted)
        val restored = disk.open().get(event.id)!!
        assertEquals(event, restored)
        assertTrue(restored.needsIndependentDispatch())
    }

    @Test fun oneFailedAttemptDoesNotBlockNextTwoEventsAndDoesNotRetryItself() {
        val disk = Disk(); var inbox = setup(disk)
        val first = accept(inbox, "one")
        assertTrue(inbox.claimIndependentDispatch(first.id))
        inbox.mark(first.id, "failed", "wake_preflight_failed")
        inbox = disk.open()
        for (id in listOf("two", "three")) {
            val next = accept(inbox, id)
            assertTrue(inbox.claimIndependentDispatch(next.id))
            assertFalse(inbox.claimIndependentDispatch(next.id))
            inbox.mark(next.id, "replied")
        }
        assertFalse(inbox.claimIndependentDispatch(first.id))
        assertTrue(inbox.get(first.id)!!.isConversationNotification(binding.assistantId, binding.conversationId))
        assertEquals(listOf("failed", "replied", "replied"), inbox.state.value.events.map { it.state })
    }

    @Test fun claimedButInterruptedReceiptCannotReplayAfterRestart() {
        val disk = Disk(); val inbox = setup(disk); val event = accept(inbox)
        assertTrue(inbox.claimIndependentDispatch(event.id))
        val reopened = disk.open()
        assertFalse(reopened.get(event.id)!!.needsIndependentDispatch())
        assertFalse(reopened.claimIndependentDispatch(event.id))
        reopened.mark(event.id, "unknown", "interrupted_before_dispatch_no_auto_retry")
        assertTrue(reopened.get(event.id)!!.isConversationNotification(binding.assistantId, binding.conversationId))
        assertTrue(reopened.claimIndependentDispatch(accept(reopened, "later").id))
    }

    @Test fun duplicateIngressCannotRearmAnAttemptOrUpgradeLegacySkippedReceipt() {
        val disk = Disk(); val inbox = setup(disk)
        val input = OrbisIncomingEvent("legacy", "lc_sentinel", "old")
        val old = inbox.accept(input, binding, 1L).first
        inbox.mark(old.id, "skipped", "wake_fresh_input_only")
        val duplicate = inbox.accept(input, binding, 3L, independentDelivery = true)
        assertTrue(duplicate.second)
        assertFalse(duplicate.first.independentDelivery)
        assertFalse(inbox.claimIndependentDispatch(old.id))
        val fresh = accept(inbox)
        assertTrue(inbox.claimIndependentDispatch(fresh.id))
        assertTrue(accept(inbox).attemptStarted)
        assertEquals(2, inbox.state.value.events.size)
    }

    @Test fun persistenceFailureCannotPublishOrClaimWork() {
        val disk = Disk(); val inbox = setup(disk); val event = accept(inbox)
        disk.fail = true
        assertTrue(runCatching { inbox.claimIndependentDispatch(event.id) }.isFailure)
        assertFalse(inbox.get(event.id)!!.attemptStarted)
        assertFalse(disk.open().get(event.id)!!.attemptStarted)
        assertTrue(runCatching { accept(inbox, "other") }.isFailure)
        assertEquals(1, inbox.state.value.events.size)
    }

    @Test fun masterSuppressionAndTargetChangeStillPreventModelClaim() {
        val disk = Disk(); val inbox = setup(disk); val event = accept(inbox)
        inbox.mark(event.id, "suppressed", "human_master_paused")
        assertFalse(inbox.claimIndependentDispatch(event.id))
        assertFalse(inbox.get(event.id)!!.isConversationNotification(binding.assistantId, binding.conversationId))
        val next = accept(inbox, "other")
        inbox.bind(setOf("lc_sentinel"), binding.copy(assistantId = "33333333-3333-4333-8333-333333333333"))
        assertFalse(inbox.claimIndependentDispatch(next.id))
        assertFalse(next.isConversationNotification("33333333-3333-4333-8333-333333333333", binding.conversationId))
    }

    @Test fun committedHistoryNeverReappearsAsPendingCardAfterCompactionOrDeletion() {
        val disk = Disk(); val inbox = setup(disk); val event = accept(inbox)
        assertTrue(inbox.claimIndependentDispatch(event.id))
        inbox.mark(event.id, "displayed")
        inbox.mark(event.id, "unknown", "model_failed")
        val persisted = disk.open().get(event.id)!!
        assertTrue(persisted.historyCommitted)
        assertFalse(persisted.isConversationNotification(binding.assistantId, binding.conversationId))
    }

    @Test fun oldJsonDefaultsDoNotResurrectHistory() {
        val disk = Disk(); val inbox = setup(disk)
        val old = inbox.accept(OrbisIncomingEvent("old", "lc_sentinel", "old"), binding, 1L).first
        disk.text = Json.encodeToString(inbox.state.value)
        val reopened = disk.open().get(old.id)!!
        assertFalse(reopened.independentDelivery)
        assertFalse(reopened.needsIndependentDispatch())
    }
}
