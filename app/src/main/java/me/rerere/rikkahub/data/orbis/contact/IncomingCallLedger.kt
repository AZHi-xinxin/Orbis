package me.rerere.rikkahub.data.orbis.contact

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class IncomingCallOutcome { RINGING, CONNECTING, CONNECTED, REJECTED, NO_RESPONSE, FAILED }

@Serializable
data class IncomingCallAttempt(
    val id: String,
    val assistantId: String,
    val conversationId: String,
    val reason: String,
    val startedAtMs: Long,
    val ringSeconds: Int,
    val outcome: IncomingCallOutcome = IncomingCallOutcome.RINGING,
    val finishedAtMs: Long? = null,
    val mutedAnswer: Boolean = false,
    val connectedCallId: String? = null,
    val failureCode: String? = null,
    /** Saved BEFORE the single fallback notification attempt. It is never replayed on recovery. */
    val fallbackAttempted: Boolean = false,
    val fallbackPosted: Boolean = false,
    val fallbackSpeech: String? = null,
    val video: Boolean = false,
)

internal interface IncomingCallStorage {
    val lockKey: String
    fun ids(): List<String>
    fun read(id: String): String?
    fun write(id: String, value: String)
}

/** Small, append-preserving per-attempt records. No automatic re-ring, redial or replay. */
class IncomingCallLedger internal constructor(private val storage: IncomingCallStorage) {
    private val mutex = locks.computeIfAbsent(storage.lockKey) { Mutex() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun create(attempt: IncomingCallAttempt): IncomingCallAttempt = locked {
        validate(attempt)
        require(attempt.outcome == IncomingCallOutcome.RINGING && attempt.finishedAtMs == null)
        check(storage.read(attempt.id) == null) { "incoming_attempt_exists" }
        save(attempt)
    }

    suspend fun get(id: String): IncomingCallAttempt? = locked { read(id) }

    suspend fun list(assistantId: String? = null, offset: Int = 0, limit: Int = 50): List<IncomingCallAttempt> = locked {
        require(offset >= 0 && limit in 1..100)
        records().filter { assistantId == null || it.assistantId == assistantId }.drop(offset).take(limit)
    }

    suspend fun cooldownRemaining(assistantId: String, now: Long, cooldownMs: Long): Long = locked {
        require(cooldownMs >= 0)
        val last = records().firstOrNull { it.assistantId == assistantId && it.outcome in
            setOf(IncomingCallOutcome.REJECTED, IncomingCallOutcome.NO_RESPONSE) }?.finishedAtMs ?: return@locked 0L
        // A clock moving backwards must not bypass a just-rejected call.
        (cooldownMs - (now - last).coerceAtLeast(0)).coerceAtLeast(0)
    }

    suspend fun update(id: String, transform: (IncomingCallAttempt) -> IncomingCallAttempt): IncomingCallAttempt = locked {
        val before = checkNotNull(read(id)) { "incoming_attempt_missing" }
        val after = transform(before)
        require(before.copy(outcome = after.outcome, finishedAtMs = after.finishedAtMs,
            mutedAnswer = after.mutedAnswer, connectedCallId = after.connectedCallId, failureCode = after.failureCode,
            fallbackAttempted = after.fallbackAttempted, fallbackPosted = after.fallbackPosted,
            fallbackSpeech = after.fallbackSpeech) == after) { "incoming_identity_changed" }
        val terminal = before.outcome !in setOf(IncomingCallOutcome.RINGING, IncomingCallOutcome.CONNECTING)
        require(!terminal || after.outcome == before.outcome) { "incoming_result_immutable" }
        require(!terminal || (before.failureCode == after.failureCode && before.mutedAnswer == after.mutedAnswer))
        require(before.fallbackSpeech == null || before.fallbackSpeech == after.fallbackSpeech)
        require(before.finishedAtMs == null || before.finishedAtMs == after.finishedAtMs)
        require(!before.fallbackAttempted || after.fallbackAttempted)
        require(!before.fallbackPosted || after.fallbackPosted)
        require(before.connectedCallId == null || before.connectedCallId == after.connectedCallId)
        if (before.outcome == IncomingCallOutcome.CONNECTING) require(after.outcome != IncomingCallOutcome.RINGING)
        validate(after)
        if (before == after) before else save(after)
    }

    /** Called once by the process owner; interrupted pending calls are failed, never replayed. */
    suspend fun recoverInterrupted(now: Long) = locked {
        records().filter { it.outcome in setOf(IncomingCallOutcome.RINGING, IncomingCallOutcome.CONNECTING) }
            .forEach { save(it.copy(outcome = IncomingCallOutcome.FAILED,
                finishedAtMs = now.coerceAtLeast(it.startedAtMs), failureCode = "process_interrupted")) }
    }

    private fun records() = storage.ids().mapNotNull(::read)
        .sortedWith(compareByDescending<IncomingCallAttempt> { it.startedAtMs }.thenBy { it.id })
    private fun read(id: String): IncomingCallAttempt? {
        validateIncomingId(id)
        val text = storage.read(id) ?: return null
        require(text.length <= 32 * 1024)
        return json.decodeFromString<IncomingCallAttempt>(text).also { require(it.id == id); validate(it) }
    }
    private fun save(value: IncomingCallAttempt): IncomingCallAttempt {
        validate(value)
        val text = json.encodeToString(value)
        require(text.toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "incoming_log_too_large" }
        storage.write(value.id, text)
        check(storage.read(value.id) == text) { "incoming_log_verify_failed" }
        return value
    }
    private fun validate(value: IncomingCallAttempt) {
        validateIncomingId(value.id)
        require(value.assistantId.isNotBlank() && value.conversationId.isNotBlank() &&
            value.assistantId.length in 1..128 && value.conversationId.length in 1..128)
        require(value.failureCode == null || value.failureCode.matches(Regex("[a-z0-9_]{1,100}")))
        require(value.connectedCallId == null || value.connectedCallId.length in 1..128)
        require(value.fallbackSpeech == null || value.fallbackSpeech in setOf("played", "skipped", "failed"))
        require(value.reason.isNotBlank() && value.reason.length <= 2000)
        require(value.ringSeconds in 5..60 && value.startedAtMs >= 0)
        require(value.finishedAtMs == null || value.finishedAtMs >= value.startedAtMs)
        val pending = value.outcome in setOf(IncomingCallOutcome.RINGING, IncomingCallOutcome.CONNECTING)
        require(pending == (value.finishedAtMs == null))
        require(value.outcome != IncomingCallOutcome.CONNECTED || !value.connectedCallId.isNullOrBlank())
        require(!value.fallbackAttempted || value.outcome == IncomingCallOutcome.NO_RESPONSE)
        require(!value.fallbackPosted || value.fallbackAttempted)
        require(value.fallbackSpeech == null || value.fallbackPosted)
    }
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}

internal fun validateIncomingId(id: String) {
    require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "invalid_incoming_id" }
}
