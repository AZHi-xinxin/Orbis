package me.rerere.rikkahub.data.orbis.sentinel

import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** All data is synthetic; the policy cannot start an observer, enqueue work, or contact a model. */
class OrbisSentinelDeliveryPolicyTest {
    @Test fun `delayed stop cancels old work but never a later resumed generation`() {
        assertTrue(sentinelBelongsToPause(event.copy(sentinelGeneration = 0), 1))
        assertTrue(sentinelBelongsToPause(event.copy(sentinelGeneration = 1), 1))
        assertFalse(sentinelBelongsToPause(event.copy(sentinelGeneration = 2), 1))
        assertFalse(sentinelBelongsToPause(event.copy(sentinelGeneration = 4), 3))
    }

    @Test fun `legacy receipts without a generation belong to an explicit stop`() {
        assertTrue(sentinelBelongsToPause(event.copy(sentinelGeneration = null), 1))
    }

    @Test fun `same millisecond pause resume invalidates prior generation`() {
        val resumed = state().copy(masterGeneration = 2, resumedAtMs = 1000)
        assertFalse(sentinelMayDeliver(resumed, event.copy(sentinelGeneration = 0)))
        assertTrue(sentinelMayDeliver(resumed, event.copy(sentinelGeneration = 2)))
    }

    @Test fun `late arrival of a paused-period external event cannot wake on resume`() {
        assertFalse(sentinelMayDeliver(state().copy(resumedAtMs = 2000), event.copy(
            source = "lc_sentinel", receivedAt = 3000, occurredAt = 1500)))
    }
    private val rule = OrbisSentinelRule(
        id = "rule-1", assistantId = "11111111-1111-4111-8111-111111111111",
        conversationId = "22222222-2222-4222-8222-222222222222",
        type = OrbisSentinelType.ONCE, dueAtMs = 1000, prompt = "synthetic", enabled = true,
    )
    private val event = OrbisInboxEvent(
        id = "synthetic-inbox-record", eventId = "native:occurrence-1", source = "native_sentinel.${rule.id}",
        text = "synthetic", wake = true, assistantId = rule.assistantId,
        conversationId = rule.conversationId, receivedAt = 1000,
    )
    private val execution = OrbisSentinelExecution(
        eventId = event.eventId, ruleId = rule.id, assistantId = rule.assistantId,
        conversationId = rule.conversationId, action = rule.action, notificationLevel = rule.notificationLevel,
        createdAtMs = 1000, status = OrbisSentinelExecutionStatus.ACCEPTED,
    )
    private fun state(enabledRule: Boolean = true) = OrbisSentinelState(rules = listOf(rule.copy(enabled = enabledRule)))

    @Test fun `human master blocks all native and legacy sources regardless of accepted receipt`() {
        for (source in listOf(event.source, "rikka_sentinel", "lc_sentinel", "self_reminder", "legacy_sentinel")) {
            assertFalse(sentinelMayDeliver(state(false).copy(enabled = false, executions = listOf(execution)), event.copy(source = source)))
        }
    }

    @Test fun `legacy behavior remains enabled by default without inventing any local rule`() {
        val empty = OrbisSentinelState()
        assertTrue(sentinelMayDeliver(empty, event.copy(source = "legacy_sentinel")))
        assertFalse(sentinelMayDeliver(empty, event))
    }

    @Test fun `resume rejects older native and legacy receipts but permits the exact new baseline`() {
        val resumed = state().copy(resumedAtMs = 2000)
        for (source in listOf(event.source, "legacy_sentinel")) {
            assertFalse(sentinelMayDeliver(resumed, event.copy(source = source, receivedAt = 1999)))
            assertTrue(sentinelMayDeliver(resumed, event.copy(source = source, receivedAt = 2000)))
        }
    }

    @Test fun `enabled native rule must have exact trusted assistant and window binding`() {
        assertTrue(sentinelMayDeliver(state(), event))
        assertFalse(sentinelMayDeliver(state(), event.copy(assistantId = "different-assistant")))
        assertFalse(sentinelMayDeliver(state(), event.copy(conversationId = "another-window-of-same-assistant")))
        assertFalse(sentinelMayDeliver(state(), event.copy(source = "native_sentinel.deleted-rule")))
        assertFalse(sentinelMayDeliver(state().copy(rules = emptyList()), event))
    }

    @Test fun `new once can enter before completion but disabled once requires exact accepted execution`() {
        assertTrue(sentinelMayDeliver(state(), event))
        assertFalse(sentinelMayDeliver(state(false), event))
        val completed = state(false).copy(executions = listOf(execution))
        assertTrue(sentinelMayDeliver(completed, event))
        assertFalse(sentinelMayDeliver(completed, event.copy(eventId = "native:different-occurrence")))
        assertFalse(sentinelMayDeliver(completed.copy(enabled = false), event))
    }

    @Test fun `one shot completion exception never applies to blocked staged cancelled or pending execution`() {
        for (status in OrbisSentinelExecutionStatus.entries.filter { it != OrbisSentinelExecutionStatus.ACCEPTED }) {
            assertFalse(sentinelMayDeliver(state(false).copy(executions = listOf(execution.copy(status = status))), event))
        }
    }

    @Test fun `accepted one shot receipt cannot be borrowed from another rule assistant or window`() {
        val wrong = listOf(
            execution.copy(ruleId = "another-rule"),
            execution.copy(assistantId = "another-assistant"),
            execution.copy(conversationId = "another-window"),
        )
        wrong.forEach { assertFalse(sentinelMayDeliver(state(false).copy(executions = listOf(it)), event)) }
        assertFalse(sentinelMayDeliver(state(false).copy(executions = listOf(execution), rules = listOf(
            rule.copy(enabled = false, conversationId = "retargeted-window"))), event))
    }

    @Test fun `accepted execution cannot bypass a paused recurring rule or human resume cutoff`() {
        val recurring = rule.copy(type = OrbisSentinelType.INTERVAL, dueAtMs = null, intervalMs = 1000, enabled = false)
        assertFalse(sentinelMayDeliver(state(false).copy(rules = listOf(recurring), executions = listOf(execution)), event))
        assertFalse(sentinelMayDeliver(state(false).copy(executions = listOf(execution), resumedAtMs = 1001), event))
    }
}
