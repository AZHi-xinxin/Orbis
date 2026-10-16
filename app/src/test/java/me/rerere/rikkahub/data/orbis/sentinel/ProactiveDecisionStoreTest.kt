package me.rerere.rikkahub.data.orbis.sentinel

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProactiveDecisionStoreTest {
    private var persisted: String? = null
    private fun store() = ProactiveDecisionStore({ persisted }, { persisted = it })
    private fun rule(event: String = UUID.randomUUID().toString()) = OrbisSentinelRule(id = "33333333-3333-4333-8333-333333333333",
        assistantId = "11111111-1111-4111-8111-111111111111", conversationId = "22222222-2222-4222-8222-222222222222",
        type = OrbisSentinelType.CHAT_IDLE, enabled = true, pendingEventId = event)
    @Test fun defaultOffAndNoClaimUntilHumanEnablesSelectedRule() {
        val store = store(); val rule = rule()
        assertFalse(store.snapshot().settings.enabled)
        assertNull(store.claim(rule, 1_000, "fingerprint"))
        store.configure(true, setOf(rule.id), 3, 30)
        assertNotNull(store.claim(rule, 1_000, "fingerprint"))
    }
    @Test fun sameEventAndRestartCannotReplayUnknownRequest() {
        val first = store(); val rule = rule(); first.configure(true, setOf(rule.id), 3, 30)
        first.claim(rule, 1_000, "fingerprint")
        val resumed = store()
        assertEquals("unknown", resumed.snapshot().receipts.single().state)
        assertNull(resumed.claim(rule, 4_000_000, "fingerprint"))
    }
    @Test fun noActionFailureAndCancelledAllConsumeDailyAllowance() {
        val store = store(); store.configure(true, setOf(rule().id), 3, 30)
        listOf("no_action", "failed", "unknown").forEachIndexed { index, state ->
            val rule = rule(); assertNotNull(store.claim(rule, 1_000 + index * 1_800_000L, "f")); store.finish(rule.pendingEventId!!, state)
        }
        assertNull(store.claim(rule(), 6_000_000, "f"))
        assertNotNull(store.claim(rule(), 90_000_000, "f"))
    }
    @Test fun cooldownAndClockRollbackFailClosed() {
        val store = store(); store.configure(true, setOf(rule().id), 3, 30)
        store.claim(rule(), 2_000_000, "f")
        assertNull(store.claim(rule(), 2_000_001, "f"))
        assertNull(store.claim(rule(), 1_999_999, "f"))
    }
    @Test fun humanSettingChangesAndContextChangeDiscardOldResult() {
        val store = store(); val rule = rule(); store.configure(true, setOf(rule.id), 3, 30)
        val receipt = store.claim(rule, 1_000, "original")!!
        assertTrue(store.permits(receipt, "original")); assertFalse(store.permits(receipt, "new-human-message"))
        store.configure(false, setOf(rule.id), 3, 30); store.configure(true, setOf(rule.id), 3, 30)
        assertFalse(store.permits(receipt, "original"))
    }
    @Test fun policyCannotDisableHardBoundsOrIncludeNewDeviceObservation() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.configure(true, setOf(rule().id), 4, 30) }
        assertThrows(IllegalArgumentException::class.java) { store.configure(true, setOf(rule().id), 3, 1) }
        store.configure(true, setOf(rule().id), 3, 30)
        assertNull(store.claim(rule().copy(action = OrbisSentinelAction.SCREENSHOT), 1_000, "f"))
    }
    @Test fun outputMustBeExactAndContainsNoUserBubbleText() {
        assertEquals(false, proactiveDecision("NO_ACTION\n")); assertEquals(true, proactiveDecision("SHOULD_SPEAK"))
        assertNull(proactiveDecision("I think NO_ACTION")); assertNull(proactiveDecision("```NO_ACTION```")); assertNull(proactiveDecision("hello"))
    }
    @Test fun receiptsDoNotContainMessagesOrPrompt() {
        val store = store(); val rule = rule().copy(prompt = "a private diary")
        store.configure(true, setOf(rule.id), 3, 30); store.claim(rule, 1_000, "hash")
        assertFalse(persisted!!.contains("a private diary"))
    }
    @Test fun droppedWriteCannotAdmitPaidClaim() {
        var dropWrites = false
        val store = ProactiveDecisionStore({ persisted }, { if (!dropWrites) persisted = it })
        val rule = rule(); store.configure(true, setOf(rule.id), 3, 30)
        dropWrites = true
        assertThrows(IllegalStateException::class.java) { store.claim(rule, 1_000, "fingerprint") }
        assertTrue(store.snapshot().receipts.isEmpty())
    }
    @Test fun writeThenThrowRetainsClaimAndRestartNeverReplaysIt() {
        var throwAfterWrite = false
        val store = ProactiveDecisionStore({ persisted }, { persisted = it; if (throwAfterWrite) error("synthetic_disk_ack_unknown") })
        val rule = rule(); store.configure(true, setOf(rule.id), 3, 30)
        throwAfterWrite = true
        assertThrows(IllegalStateException::class.java) { store.claim(rule, 1_000, "fingerprint") }
        val restarted = store()
        assertEquals("unknown", restarted.snapshot().receipts.single().state)
        assertNull(restarted.claim(rule, 3_000_000, "fingerprint"))
    }
}
