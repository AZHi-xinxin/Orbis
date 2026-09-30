package me.rerere.rikkahub.data.orbis.sentinel

import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import me.rerere.rikkahub.data.orbis.isNativeSentinelSource

/** An asynchronous stop must never cancel events accepted after a subsequent human resume. */
fun sentinelBelongsToPause(event: OrbisInboxEvent, pausedGeneration: Long): Boolean =
    (event.sentinelGeneration ?: 0L) <= pausedGeneration

/** Pure human-master and trusted-target gate; inbox delivery-state checks remain with its owner. */
fun sentinelMayDeliver(state: OrbisSentinelState, event: OrbisInboxEvent): Boolean {
    if (!state.enabled || (state.resumedAtMs != null && minOf(event.receivedAt, event.occurredAt ?: event.receivedAt) < state.resumedAtMs)) return false
    if (event.sentinelGeneration != null && event.sentinelGeneration != state.masterGeneration) return false
    if (!isNativeSentinelSource(event.source)) return true
    val rule = state.rules.firstOrNull { it.id == event.source.removePrefix("native_sentinel.") } ?: return false
    if (rule.assistantId != event.assistantId || rule.conversationId != event.conversationId) return false
    if (rule.enabled) return true
    // Successful one-shots auto-disable after durable acceptance. Only that exact receipt can finish.
    return rule.type == OrbisSentinelType.ONCE && state.executions.any {
        it.ruleId == rule.id && it.eventId == event.eventId &&
            it.assistantId == event.assistantId && it.conversationId == event.conversationId &&
            it.status == OrbisSentinelExecutionStatus.ACCEPTED
    }
}
