package me.rerere.rikkahub.data.orbis

import kotlinx.serialization.Serializable

/** Counts refer to removed display history, never to rules, chat messages or attachments. */
@Serializable
data class OrbisHistoryCleanupReceipt(
    val operationId: String,
    val removed: Int,
    val remaining: Int,
    val protected: Int,
    val bytesBefore: Int,
    val bytesAfter: Int,
    val completedAt: Long,
)

data class OrbisHistoryPreview(val candidates: Int, val protected: Int, val bytes: Int)

data class OrbisCombinedHistoryPreview(val deliveries: OrbisHistoryPreview, val executions: OrbisHistoryPreview)
data class OrbisCombinedHistoryResult(val operationId: String,
    val deliveries: OrbisHistoryCleanupReceipt?, val executions: OrbisHistoryCleanupReceipt?,
    val error: String? = null)

data class OrbisHistoryCompletion(val source: String, val eventId: String, val assistantId: String, val conversationId: String)

private fun OrbisInboxState.completedEventIds(): Set<OrbisHistoryCompletion> =
    (events.asSequence() + historyReceipts.asSequence().map { it.event }).filter {
        (it.state == "replied" || it.state == "displayed" && !it.wake) &&
            (!it.independentDelivery || it.historyCommitted)
    }.map { OrbisHistoryCompletion(it.source, it.eventId, it.assistantId, it.conversationId) }.toSet()

fun previewOrbisHistory(inbox: OrbisEventInbox, rules: me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelRuleStore,
    now: Long, keepDays: Int): OrbisCombinedHistoryPreview {
    val pending = rules.refresh().rules.mapNotNull { it.pendingEventId }.toSet()
    return OrbisCombinedHistoryPreview(inbox.historyPreview(now, keepDays, pending),
        rules.historyPreview(now, keepDays, inbox.state.value.completedEventIds()))
}

/** Two independently safe atomic commits. Partial success is explicitly reported, not rolled back
 * into an executable queue. Same operation IDs resume a committed first stage without losing proof. */
fun cleanOrbisHistory(inbox: OrbisEventInbox, rules: me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelRuleStore,
    now: Long, keepDays: Int, operationId: String): OrbisCombinedHistoryResult {
    var deliveries: OrbisHistoryCleanupReceipt? = null
    var executions: OrbisHistoryCleanupReceipt? = null
    return try {
        deliveries = inbox.cleanHistory(now, keepDays, rules.refresh().rules.mapNotNull { it.pendingEventId }.toSet(), operationId)
        executions = rules.cleanHistory(now, keepDays, inbox.state.value.completedEventIds(), operationId)
        OrbisCombinedHistoryResult(operationId, deliveries, executions)
    } catch (_: Exception) {
        // Read back receipts after a write-then-error. Never claim both files changed atomically.
        deliveries = inbox.state.value.lastCleanup?.takeIf { it.operationId == operationId } ?: deliveries
        executions = runCatching { rules.refresh().lastCleanup?.takeIf { it.operationId == operationId } }.getOrNull() ?: executions
        OrbisCombinedHistoryResult(operationId, deliveries, executions, "history_cleanup_not_fully_confirmed")
    }
}

fun historyCutoff(now: Long, keepDays: Int): Long {
    require(now >= 0 && keepDays in 1..365) { "invalid_history_retention" }
    return (now - keepDays * 86_400_000L).coerceAtLeast(0)
}

@Serializable
data class OrbisHistoryRetention(val automatic: Boolean = false, val keepDays: Int = 5,
    val lastAttemptAt: Long? = null)

/** Do not run extra catch-up passes when the clock is moved backwards. */
fun OrbisHistoryRetention.isDue(now: Long): Boolean = automatic &&
    (lastAttemptAt == null || now >= lastAttemptAt && now - lastAttemptAt >= 86_400_000L)
