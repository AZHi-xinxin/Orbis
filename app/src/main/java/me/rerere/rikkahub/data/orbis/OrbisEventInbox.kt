package me.rerere.rikkahub.data.orbis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

val ORBIS_EVENT_SOURCES = setOf("rikka_sentinel", "lc_sentinel", "self_reminder", "legacy_sentinel")

/** Native rule identities are internal only; HTTP routes retain their static source allow-list. */
fun isNativeSentinelSource(source: String): Boolean = source.startsWith("native_sentinel.") &&
    Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}").matches(source.removePrefix("native_sentinel."))
fun isImageWorkshopSource(source: String): Boolean = source.startsWith("image_workshop.") &&
    Regex("[a-f0-9-]{36}").matches(source.removePrefix("image_workshop."))
fun isPetEventSource(source: String) = source.startsWith("pet_room.") &&
    Regex("[a-f0-9-]{36}").matches(source.removePrefix("pet_room."))
private fun validEventSource(source: String) = source in ORBIS_EVENT_SOURCES || isNativeSentinelSource(source) || isImageWorkshopSource(source) || isPetEventSource(source)

@Serializable
data class OrbisEventBinding(val assistantId: String, val conversationId: String, val enabled: Boolean = true)

@Serializable
data class OrbisIncomingEvent(val event_id: String, val source: String, val text: String, val wake: Boolean = true,
    val occurred_at: Long? = null, val localImage: String? = null,
    val workshopImages: List<String> = emptyList())

@Serializable
data class OrbisInboxEvent(
    val id: String = UUID.randomUUID().toString(),
    val eventId: String,
    val source: String,
    val text: String,
    val wake: Boolean,
    val assistantId: String,
    val conversationId: String,
    val receivedAt: Long,
    val state: String = "accepted",
    val error: String? = null,
    val occurredAt: Long? = null,
    val localImage: String? = null,
    val sentinelGeneration: Long? = null,
    // New deliveries are visible independently of model admission. Legacy receipts never opt in.
    val independentDelivery: Boolean = false,
    val attemptStarted: Boolean = false,
    val historyCommitted: Boolean = false,
    val workshopImages: List<String> = emptyList(),
    val updatedAt: Long? = null,
    // Presentation only: closing a notification never acknowledges or replays its work.
    val notificationDismissed: Boolean = false,
)

fun OrbisInboxEvent.needsIndependentDispatch(): Boolean =
    independentDelivery && !attemptStarted && state == "accepted"

fun OrbisInboxEvent.isConversationNotification(assistant: String, conversation: String): Boolean =
    independentDelivery && !historyCommitted && !notificationDismissed && assistantId == assistant && conversationId == conversation &&
        state !in setOf("suppressed", "target_invalid")

@Serializable
data class OrbisInboxState(val version: Int = 1,
    val bindings: Map<String, OrbisEventBinding> = emptyMap(),
    val events: List<OrbisInboxEvent> = emptyList(),
    val historyReceipts: List<OrbisEventHistoryReceipt> = emptyList(),
    val lastCleanup: OrbisHistoryCleanupReceipt? = null)

/** Metadata is retained for deduplication and scheduler high-water marks; original text is gone. */
@Serializable
data class OrbisEventHistoryReceipt(val event: OrbisInboxEvent, val contentHash: String)

fun OrbisInboxState.lastWakeAt(conversationId: String): Long =
    (events.asSequence() + historyReceipts.asSequence().map { it.event }).filter {
        it.conversationId == conversationId && it.state !in setOf("suppressed", "skipped", "failed", "target_invalid")
    }.maxOfOrNull { it.receivedAt } ?: 0L

/** One durable receipt per source/id. The state is committed before publishing or queueing.
 * Read/serialization/storage failures never silently replace the existing inbox with an empty one.
 * History cleanup is explicit or human opt-in and always retains terminal deduplication receipts.
 */
class OrbisEventInbox(private val read: () -> String?, private val write: (String) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(read()?.let {
        require(it.toByteArray().size <= MAX_BYTES) { "event_inbox_too_large" }
        json.decodeFromString<OrbisInboxState>(it).also { state ->
            require(state.version == 1) { "event_inbox_version_unsupported" }
        }
    } ?: OrbisInboxState())
    val state = mutable.asStateFlow()
    private var recoveryNeeded = false

    /** A failed read-back blocks mutations until the committed disk state can be recovered. */
    private fun ensureReadable() {
        if (!recoveryNeeded) return
        val raw = read() ?: run {
            check(mutable.value == OrbisInboxState()) { "event_inbox_recovery_required" }
            recoveryNeeded = false
            return
        }
        require(raw.toByteArray().size <= MAX_BYTES) { "event_inbox_too_large" }
        val value = json.decodeFromString<OrbisInboxState>(raw)
        require(value.version == 1) { "event_inbox_version_unsupported" }
        mutable.value = value
        recoveryNeeded = false
    }

    @Synchronized
    fun bind(sources: Set<String>, binding: OrbisEventBinding) {
        ensureReadable()
        require(sources.isNotEmpty() && sources.all(::validEventSource)) { "invalid_event_source" }
        UUID.fromString(binding.assistantId); UUID.fromString(binding.conversationId)
        commit(state.value.copy(bindings = state.value.bindings + sources.associateWith { binding }))
    }

    @Synchronized
    fun binding(source: String): OrbisEventBinding? { ensureReadable(); return state.value.bindings[source]?.takeIf { it.enabled } }

    @Synchronized
    fun accept(input: OrbisIncomingEvent, expected: OrbisEventBinding, now: Long, sentinelGeneration: Long? = null,
        independentDelivery: Boolean = false): Pair<OrbisInboxEvent, Boolean> {
        ensureReadable()
        require(validEventSource(input.source)) { "invalid_event_source" }
        require(input.localImage == null || (isNativeSentinelSource(input.source) &&
            Regex("[a-f0-9]{64}\\.jpg").matches(input.localImage))) { "invalid_event_attachment" }
        require(input.workshopImages.isEmpty() || (isImageWorkshopSource(input.source) &&
            input.workshopImages.size <= 16 && input.workshopImages.distinct().size == input.workshopImages.size &&
            input.workshopImages.all { Regex("[a-f0-9]{64}\\.(png|jpg|webp)").matches(it) })) { "invalid_event_attachment" }
        require(input.event_id.length in 1..180 && input.event_id.all { it.isLetterOrDigit() || it in ":._-" }) { "invalid_event_id" }
        require(input.text.isNotBlank() && input.text.toByteArray().size <= 64 * 1024) { "invalid_event_text" }
        require(input.occurred_at == null || (input.occurred_at in 1L..253402300799999L &&
            input.occurred_at <= now.coerceAtMost(Long.MAX_VALUE - 300_000L) + 300_000L)) { "invalid_event_time" }
        state.value.events.firstOrNull { it.source == input.source && it.eventId == input.event_id }?.let {
            require(it.text == input.text && it.wake == input.wake && it.localImage == input.localImage &&
                it.workshopImages == input.workshopImages) { "event_id_conflict" }
            // Compatibility replays may lack a timestamp. Never backfill or alter the first receipt.
            require(it.occurredAt == null || input.occurred_at == null || it.occurredAt == input.occurred_at) { "event_id_conflict" }
            return it to true
        }
        state.value.historyReceipts.firstOrNull { it.event.source == input.source && it.event.eventId == input.event_id }?.let {
            require(it.contentHash == contentHash(input.text, input.wake, input.localImage, input.workshopImages)) { "event_id_conflict" }
            require(it.event.occurredAt == null || input.occurred_at == null || it.event.occurredAt == input.occurred_at) { "event_id_conflict" }
            return it.event to true
        }
        require(binding(input.source) == expected) { "event_target_changed" }
        require(state.value.events.size < 2000) { "event_inbox_full" }
        val event = OrbisInboxEvent(eventId = input.event_id, source = input.source,
            text = input.text, wake = input.wake, assistantId = expected.assistantId,
            conversationId = expected.conversationId, receivedAt = now, occurredAt = input.occurred_at,
            localImage = input.localImage, sentinelGeneration = sentinelGeneration,
            independentDelivery = independentDelivery, workshopImages = input.workshopImages)
        commit(state.value.copy(events = state.value.events + event))
        return event to false
    }

    @Synchronized
    fun get(id: String): OrbisInboxEvent? { ensureReadable(); return state.value.events.firstOrNull { it.id == id }
        ?: state.value.historyReceipts.firstOrNull { it.event.id == id }?.event }

    @Synchronized
    fun dismissNotification(id: String, assistantId: String, conversationId: String) {
        val event = get(id) ?: return
        require(event.assistantId == assistantId && event.conversationId == conversationId) { "event_target_changed" }
        if (!event.independentDelivery || event.notificationDismissed) return
        commit(state.value.copy(events = state.value.events.map {
            if (it.id == id) it.copy(notificationDismissed = true) else it
        }))
    }

    /** Commit the one-shot claim BEFORE starting a coroutine/model. Crashes cannot replay it. */
    @Synchronized
    fun claimIndependentDispatch(id: String): Boolean {
        val event = get(id) ?: return false
        if (!event.needsIndependentDispatch() || !targetStillMatches(event)) return false
        commit(state.value.copy(events = state.value.events.map {
            if (it.id == id) it.copy(attemptStarted = true, state = "queued", error = null) else it
        }))
        return true
    }

    /** Recovery authority requires a disk read-back, not only the published in-memory receipts. */
    @Synchronized
    fun verifySuppressed(conversationId: String, ids: Set<String>): Boolean = try {
        val raw = read()
        val persisted = if (raw == null) {
            check(state.value == OrbisInboxState())
            OrbisInboxState()
        } else {
            require(raw.toByteArray().size <= MAX_BYTES)
            json.decodeFromString<OrbisInboxState>(raw)
        }
        persisted == state.value && ids.all { id -> persisted.events.any {
            it.id == id && it.conversationId == conversationId && it.state == "suppressed"
        } }
    } catch (_: Exception) { false }

    @Synchronized
    fun receipt(source: String, eventId: String): OrbisInboxEvent? { ensureReadable(); return state.value.events.firstOrNull {
        it.source == source && it.eventId == eventId
    } ?: state.value.historyReceipts.firstOrNull { it.event.source == source && it.event.eventId == eventId }?.event }

    @Synchronized
    fun mark(id: String, status: String, error: String? = null, now: Long = System.currentTimeMillis()) {
        ensureReadable()
        require(status in STATES)
        // A late callback can never recreate a cleaned-up terminal delivery.
        if (state.value.historyReceipts.any { it.event.id == id }) return
        check(state.value.events.any { it.id == id }) { "event_missing" }
        // A late cancelled/completed coroutine must not overwrite an explicit human suppression.
        if (state.value.events.first { it.id == id }.state in setOf("suppressed", "skipped")) return
        commit(state.value.copy(events = state.value.events.map {
            if (it.id == id) it.copy(state = status, error = error, updatedAt = maxOf(now, it.receivedAt, it.updatedAt ?: 0),
                historyCommitted = it.historyCommitted || it.independentDelivery &&
                    status in setOf("displayed", "generating", "replied", "pending_tool")) else it
        }))
    }

    @Synchronized
    fun targetStillMatches(event: OrbisInboxEvent): Boolean = binding(event.source)?.let {
        it.assistantId == event.assistantId && it.conversationId == event.conversationId
    } == true

    /** Only proven completed notifications. Recovery evidence and workshop jobs stay untouched. */
    private fun candidates(before: OrbisInboxState, cutoff: Long, protectedEventIds: Set<String>) = before.events.filter {
        it.receivedAt < cutoff && (it.updatedAt ?: it.receivedAt) < cutoff &&
            it.eventId !in protectedEventIds && !isImageWorkshopSource(it.source) &&
            (it.state == "replied" || it.state == "displayed" && !it.wake) &&
            (!it.independentDelivery || it.historyCommitted)
    }

    @Synchronized
    fun historyPreview(now: Long, keepDays: Int, protectedEventIds: Set<String> = emptySet()): OrbisHistoryPreview {
        ensureReadable()
        val before = state.value
        val found = candidates(before, historyCutoff(now, keepDays), protectedEventIds)
        return OrbisHistoryPreview(found.size, before.events.size - found.size, json.encodeToString(before).toByteArray().size)
    }

    /** Receipt and history replacement are one atomic-file commit. Retrying an operation is safe. */
    @Synchronized
    fun cleanHistory(now: Long, keepDays: Int, protectedEventIds: Set<String>, operationId: String): OrbisHistoryCleanupReceipt {
        ensureReadable()
        require(runCatching { UUID.fromString(operationId).toString() == operationId }.getOrDefault(false)) { "invalid_cleanup_operation" }
        val before = state.value
        before.lastCleanup?.takeIf { it.operationId == operationId }?.let { return it }
        val found = candidates(before, historyCutoff(now, keepDays), protectedEventIds)
        val ids = found.map { it.id }.toSet()
        val after = before.copy(events = before.events.filterNot { it.id in ids }, historyReceipts = before.historyReceipts + found.map {
            OrbisEventHistoryReceipt(it.copy(text = "", error = null), contentHash(it.text, it.wake, it.localImage, it.workshopImages))
        })
        var result = OrbisHistoryCleanupReceipt(operationId, found.size, after.events.size, before.events.size - found.size,
            json.encodeToString(before).toByteArray().size, 0, now)
        // Include the persisted operation receipt itself in the actual serialized-byte count.
        repeat(3) { result = result.copy(bytesAfter = json.encodeToString(after.copy(lastCleanup = result)).toByteArray().size) }
        commit(after.copy(lastCleanup = result))
        return result
    }

    private fun contentHash(text: String, wake: Boolean, image: String?, images: List<String>): String {
        val canonical = json.encodeToString(listOf(text, wake.toString(), image) + images)
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun commit(value: OrbisInboxState) {
        val serialized = json.encodeToString(value)
        require(serialized.toByteArray().size <= MAX_BYTES) { "event_inbox_full" }
        try {
            write(serialized)
            check(read() == serialized) { "event_inbox_verify_failed" }
            mutable.value = value
        } catch (error: Exception) {
            recoveryNeeded = true
            // A write may have succeeded before throwing. Recover if readable; otherwise stay locked.
            runCatching { ensureReadable() }
            throw error
        }
    }

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        val STATES = setOf("accepted", "queued", "displayed", "generating", "replied", "pending_tool", "unknown", "target_invalid", "failed", "suppressed", "skipped")
        fun tokenMatches(expected: String, actual: String?): Boolean = expected.length >= 32 &&
            actual != null && actual.length <= 256 && MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
    }
}
