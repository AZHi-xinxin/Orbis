package me.rerere.rikkahub.data.orbis

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class QueuePauseStatus { PAUSED, UNPAUSED, UNAVAILABLE }

@Serializable
private data class QueuePauseState(
    val version: Int,
    val legacyMigrationDone: Boolean,
    val pauses: Map<String, String>,
)

/**
 * Durable per-conversation pause metadata, never message content. The owner supplies an atomic
 * write (Android AtomicFile in production) for orbis-queue-pause-v1.json. Missing on the first read is
 * a fresh store; malformed/unreadable/disappearing storage is NOT an empty/resumed store.
 * No constructor writes, background repair, deletion, or fallback over a damaged original.
 */
class OrbisQueuePauseStore(private val read: () -> String?, private val write: (String) -> Unit) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private var storageSeenOrReadFailed = false
    private val uncertain = mutableSetOf<String>()

    /** Safe diagnostic code only; never includes file contents or an underlying exception text. */
    @Volatile var lastFailure: String? = null
        private set

    @Synchronized
    fun status(conversationId: String): QueuePauseStatus {
        validateId(conversationId)
        return try {
            val state = load()
            if (conversationId in uncertain || conversationId in state.pauses) QueuePauseStatus.PAUSED
            else QueuePauseStatus.UNPAUSED
        } catch (_: Exception) {
            QueuePauseStatus.UNAVAILABLE
        }
    }

    /** Existing conversations fail closed. UI/new-empty-conversation policy belongs to the caller. */
    @Synchronized
    fun isPaused(conversationId: String): Boolean = status(conversationId) != QueuePauseStatus.UNPAUSED

    @Synchronized
    fun pause(conversationId: String, reason: String = "generation_or_human_pause") {
        validateId(conversationId)
        require(reason.matches(Regex("[a-z0-9_]{1,80}"))) { "invalid_queue_pause_reason" }
        val before = load()
        if (conversationId in before.pauses && conversationId !in uncertain) return
        commit(before.copy(pauses = before.pauses + (conversationId to reason)), setOf(conversationId))
    }

    /** Explicit, durable acknowledgement. Failed writes never authorize an in-memory resume. */
    @Synchronized
    fun resume(conversationId: String) {
        validateId(conversationId)
        val before = load()
        if (conversationId !in before.pauses && conversationId !in uncertain) return
        commit(before.copy(pauses = before.pauses - conversationId), setOf(conversationId))
    }

    /**
     * One-time migration of pre-store queued receipts. Infer only the last actually dispatched
     * receipt and only if later undispatched input remains. Do not repeatedly reinterpret newer
     * known-empty failures (also stored as unknown) as new pauses on every process restart.
     * A genuinely interrupted generating receipt is separately paused by the caller on restore.
     */
    @Synchronized
    fun migrateLegacy(events: List<OrbisInboxEvent>) {
        val before = load()
        if (before.legacyMigrationDone) return
        val inherited = mutableSetOf<String>()
        events.withIndex().groupBy { it.value.conversationId }.forEach { (conversationId, indexed) ->
            val ordered = indexed.sortedWith(compareBy({ it.value.receivedAt }, { it.index }))
            val lastDispatchedIndex = ordered.indexOfLast { it.value.state !in UNDISPATCHED_OR_IGNORED }
            if (lastDispatchedIndex < 0) return@forEach
            if (ordered[lastDispatchedIndex].value.state !in UNCERTAIN_COMPLETIONS) return@forEach
            if (ordered.drop(lastDispatchedIndex + 1).none { it.value.state in PENDING }) return@forEach
            validateId(conversationId)
            inherited += conversationId
        }
        // Existing explicit pauses own their reason; migration can add but never remove them.
        val migrated = inherited.associateWith { "legacy_failure_with_pending_input" } + before.pauses
        commit(before.copy(legacyMigrationDone = true, pauses = migrated), inherited)
    }

    private fun load(): QueuePauseState {
        val raw = try { read() } catch (failure: Exception) {
            storageSeenOrReadFailed = true
            lastFailure = "queue_pause_read_failed"
            throw IllegalStateException(lastFailure, failure)
        }
        if (raw == null) {
            if (storageSeenOrReadFailed) {
                lastFailure = "queue_pause_storage_disappeared"
                error(checkNotNull(lastFailure))
            }
            return QueuePauseState(version = 1, legacyMigrationDone = false, pauses = emptyMap())
        }
        storageSeenOrReadFailed = true
        return try {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "queue_pause_store_too_large" }
            json.decodeFromString<QueuePauseState>(raw).also { validateState(it) }
        } catch (failure: Exception) {
            lastFailure = "queue_pause_invalid_storage"
            throw IllegalStateException(lastFailure, failure)
        }
    }

    private fun commit(state: QueuePauseState, affected: Set<String>) {
        validateState(state)
        val encoded = json.encodeToString(state)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "queue_pause_store_too_large" }
        try {
            write(encoded)
            // Do not publish a successful resume from an adapter that silently failed to commit.
            check(read() == encoded) { "queue_pause_write_unverified" }
            storageSeenOrReadFailed = true
            uncertain.removeAll(affected)
            lastFailure = null
        } catch (failure: Exception) {
            uncertain.addAll(affected)
            lastFailure = "queue_pause_write_failed"
            throw IllegalStateException(lastFailure, failure)
        }
    }

    private fun validateState(state: QueuePauseState) {
        require(state.version == 1) { "queue_pause_version_unsupported" }
        require(state.pauses.size <= MAX_PAUSES) { "queue_pause_store_too_large" }
        state.pauses.forEach { (id, reason) ->
            validateId(id)
            require(reason.matches(Regex("[a-z0-9_]{1,80}"))) { "invalid_queue_pause_reason" }
        }
    }

    private fun validateId(id: String) {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
            "invalid_queue_pause_conversation_id"
        }
    }

    companion object {
        const val FILE_NAME = "orbis-queue-pause-v1.json"
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_PAUSES = 10_000
        private val PENDING = setOf("accepted", "queued")
        private val UNDISPATCHED_OR_IGNORED = PENDING + setOf("suppressed", "target_invalid")
        private val UNCERTAIN_COMPLETIONS = setOf("unknown", "generating", "failed")
    }
}
