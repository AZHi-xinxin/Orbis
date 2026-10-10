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
private fun validEventSource(source: String) = source in ORBIS_EVENT_SOURCES || isNativeSentinelSource(source)

@Serializable
data class OrbisEventBinding(val assistantId: String, val conversationId: String, val enabled: Boolean = true)

@Serializable
data class OrbisIncomingEvent(val event_id: String, val source: String, val text: String, val wake: Boolean = true,
    val occurred_at: Long? = null, val localImage: String? = null)

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
)

@Serializable
data class OrbisInboxState(val version: Int = 1,
    val bindings: Map<String, OrbisEventBinding> = emptyMap(),
    val events: List<OrbisInboxEvent> = emptyList())

/** One durable receipt per source/id. The state is committed before publishing or queueing.
 * Read/serialization/storage failures never silently replace the existing inbox with an empty one.
 * There is deliberately no automatic pruning of a user's event archive.
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

    @Synchronized
    fun bind(sources: Set<String>, binding: OrbisEventBinding) {
        require(sources.isNotEmpty() && sources.all(::validEventSource)) { "invalid_event_source" }
        UUID.fromString(binding.assistantId); UUID.fromString(binding.conversationId)
        commit(state.value.copy(bindings = state.value.bindings + sources.associateWith { binding }))
    }

    @Synchronized
    fun binding(source: String): OrbisEventBinding? = state.value.bindings[source]?.takeIf { it.enabled }

    @Synchronized
    fun accept(input: OrbisIncomingEvent, expected: OrbisEventBinding, now: Long, sentinelGeneration: Long? = null): Pair<OrbisInboxEvent, Boolean> {
        require(validEventSource(input.source)) { "invalid_event_source" }
        require(input.localImage == null || (isNativeSentinelSource(input.source) &&
            Regex("[a-f0-9]{64}\\.jpg").matches(input.localImage))) { "invalid_event_attachment" }
        require(input.event_id.length in 1..180 && input.event_id.all { it.isLetterOrDigit() || it in ":._-" }) { "invalid_event_id" }
        require(input.text.isNotBlank() && input.text.toByteArray().size <= 64 * 1024) { "invalid_event_text" }
        require(input.occurred_at == null || (input.occurred_at in 1L..253402300799999L &&
            input.occurred_at <= now.coerceAtMost(Long.MAX_VALUE - 300_000L) + 300_000L)) { "invalid_event_time" }
        state.value.events.firstOrNull { it.source == input.source && it.eventId == input.event_id }?.let {
            require(it.text == input.text && it.wake == input.wake && it.localImage == input.localImage) { "event_id_conflict" }
            // Compatibility replays may lack a timestamp. Never backfill or alter the first receipt.
            require(it.occurredAt == null || input.occurred_at == null || it.occurredAt == input.occurred_at) { "event_id_conflict" }
            return it to true
        }
        require(binding(input.source) == expected) { "event_target_changed" }
        require(state.value.events.size < 2000) { "event_inbox_full" }
        val event = OrbisInboxEvent(eventId = input.event_id, source = input.source,
            text = input.text, wake = input.wake, assistantId = expected.assistantId,
            conversationId = expected.conversationId, receivedAt = now, occurredAt = input.occurred_at,
            localImage = input.localImage, sentinelGeneration = sentinelGeneration)
        commit(state.value.copy(events = state.value.events + event))
        return event to false
    }

    @Synchronized
    fun get(id: String): OrbisInboxEvent? = state.value.events.firstOrNull { it.id == id }

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
    fun receipt(source: String, eventId: String): OrbisInboxEvent? = state.value.events.firstOrNull {
        it.source == source && it.eventId == eventId
    }

    @Synchronized
    fun mark(id: String, status: String, error: String? = null) {
        require(status in STATES)
        check(state.value.events.any { it.id == id }) { "event_missing" }
        // A late cancelled/completed coroutine must not overwrite an explicit human suppression.
        if (state.value.events.first { it.id == id }.state in setOf("suppressed", "skipped")) return
        commit(state.value.copy(events = state.value.events.map {
            if (it.id == id) it.copy(state = status, error = error) else it
        }))
    }

    @Synchronized
    fun targetStillMatches(event: OrbisInboxEvent): Boolean = binding(event.source)?.let {
        it.assistantId == event.assistantId && it.conversationId == event.conversationId
    } == true

    private fun commit(value: OrbisInboxState) {
        val serialized = json.encodeToString(value)
        require(serialized.toByteArray().size <= MAX_BYTES) { "event_inbox_full" }
        write(serialized)
        mutable.value = value
    }

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        val STATES = setOf("accepted", "queued", "displayed", "generating", "replied", "pending_tool", "unknown", "target_invalid", "failed", "suppressed", "skipped")
        fun tokenMatches(expected: String, actual: String?): Boolean = expected.length >= 32 &&
            actual != null && actual.length <= 256 && MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
    }
}
