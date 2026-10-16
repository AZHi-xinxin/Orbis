package me.rerere.rikkahub.data.orbis.sentinel

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProactiveDecisionSettings(
    val enabled: Boolean = false,
    val ruleIds: Set<String> = emptySet(),
    val maxPerDay: Int = 3,
    val cooldownMinutes: Int = 30,
    val revision: Long = 0,
)

@Serializable
data class ProactiveDecisionReceipt(
    val eventId: String, val ruleId: String, val assistantId: String, val conversationId: String,
    val claimedAt: Long, val settingsRevision: Long, val contextFingerprint: String,
    val state: String = "claimed",
)

@Serializable
data class ProactiveDecisionState(val settings: ProactiveDecisionSettings = ProactiveDecisionSettings(),
    val receipts: List<ProactiveDecisionReceipt> = emptyList())

/** Human-owned policy and content-free one-shot receipts. Unknown paid calls are never replayed. */
class ProactiveDecisionStore(private val read: () -> String?, private val write: (String) -> Unit) {
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var initialized = false
    @Synchronized fun snapshot(): ProactiveDecisionState {
        val content = read()
        require(content == null || content.length <= 524_288) { "proactive_store_limit" }
        var state = content?.let { codec.decodeFromString<ProactiveDecisionState>(it) } ?: ProactiveDecisionState()
        validate(state.settings)
        require(state.receipts.size <= 200 && state.receipts.all {
            it.state in setOf("claimed", "speak", "no_action", "unknown", "stale", "failed") &&
                it.contextFingerprint.length in 1..256 && it.eventId.length in 1..256
        }) { "proactive_receipt_invalid" }
        if (!initialized) {
            // Only this process's in-memory jobs can finish a claimed request.
            state = state.copy(receipts = state.receipts.map { if (it.state == "claimed") it.copy(state = "unknown") else it })
            save(state); initialized = true
        }
        return state
    }
    @Synchronized fun configure(enabled: Boolean, ruleIds: Set<String>, maxPerDay: Int, cooldownMinutes: Int): ProactiveDecisionSettings {
        val before = snapshot()
        val settings = ProactiveDecisionSettings(enabled, ruleIds, maxPerDay, cooldownMinutes, Math.addExact(before.settings.revision, 1))
        validate(settings)
        save(before.copy(settings = settings)); return settings
    }
    @Synchronized fun claim(rule: OrbisSentinelRule, now: Long, fingerprint: String): ProactiveDecisionReceipt? {
        val before = snapshot(); val settings = before.settings
        val eventId = rule.pendingEventId ?: return null
        if (!settings.enabled || rule.id !in settings.ruleIds || !supports(rule) || before.receipts.any { it.eventId == eventId }) return null
        val recent = before.receipts.filter { now - it.claimedAt < DAY_MS }
        if (recent.any { it.claimedAt > now } || recent.size >= settings.maxPerDay ||
            recent.any { now - it.claimedAt < settings.cooldownMinutes * 60_000L }) return null
        val receipt = ProactiveDecisionReceipt(eventId, rule.id, rule.assistantId, rule.conversationId, now, settings.revision, fingerprint)
        // Keep all requests inside the quota window, plus a bounded no-content history.
        save(before.copy(receipts = (before.receipts.takeLast(199) + receipt)))
        return receipt
    }
    @Synchronized fun finish(eventId: String, state: String) {
        require(state in setOf("speak", "no_action", "unknown", "stale", "failed"))
        val before = snapshot()
        save(before.copy(receipts = before.receipts.map { if (it.eventId == eventId && it.state == "claimed") it.copy(state = state) else it }))
    }
    @Synchronized fun permits(receipt: ProactiveDecisionReceipt, fingerprint: String): Boolean {
        val state = snapshot()
        return state.settings.enabled && state.settings.revision == receipt.settingsRevision && receipt.ruleId in state.settings.ruleIds &&
            receipt.contextFingerprint == fingerprint && state.receipts.any { it.eventId == receipt.eventId && it.state == "claimed" }
    }
    private fun save(state: ProactiveDecisionState) {
        val content = codec.encodeToString(state)
        require(content.length <= 524_288) { "proactive_store_limit" }
        write(content)
        // Do not start a paid request until the durable claim can be read back exactly.
        // A writer that persisted then threw must remain a failed/unknown occurrence, never retry.
        check(read() == content) { "proactive_persistence_unconfirmed" }
    }
    private fun validate(settings: ProactiveDecisionSettings) {
        require(settings.maxPerDay in 1..3 && settings.cooldownMinutes in 30..1440 && settings.ruleIds.size <= 256 && settings.revision >= 0)
        require(settings.ruleIds.all { runCatching { java.util.UUID.fromString(it) }.isSuccess })
    }
    companion object {
        const val DAY_MS = 86_400_000L
        fun supports(rule: OrbisSentinelRule): Boolean = rule.type in setOf(OrbisSentinelType.CHAT_IDLE, OrbisSentinelType.INTERVAL) && rule.action == OrbisSentinelAction.WAKE
    }
}

/** Strict single-token decision; extra text, malformed replies and tool requests fail closed. */
internal fun proactiveDecision(text: String): Boolean? = when (text.trim()) {
    "SHOULD_SPEAK" -> true
    "NO_ACTION" -> false
    else -> null
}
