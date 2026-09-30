package me.rerere.rikkahub.data.orbis.voice

import android.content.Context
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Each write must be atomic and durably verified; a thrown write may already have committed. */
internal interface OrbisVoiceCallStorage {
    val lockKey: String
    fun ids(): List<String>
    fun read(id: String): String?
    fun write(id: String, value: String)
}

/**
 * Separate app-private archive. No chat database writes and deliberately no deletion API.
 * Every operation rereads disk under the directory's shared mutex, including after a failed write.
 */
class OrbisVoiceCallRepository internal constructor(private val storage: OrbisVoiceCallStorage) {
    constructor(context: Context) : this(AndroidVoiceCallStorage(context.applicationContext))

    private val mutex = locks.computeIfAbsent(storage.lockKey) { Mutex() }
    private val changes = revisions.computeIfAbsent(storage.lockKey) { MutableStateFlow(0L) }
    /** Shared by every repository for this archive; observers reload durable data on a change. */
    val revision = changes.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun create(record: OrbisVoiceCallRecord): OrbisVoiceCallRecord = locked {
        validate(record)
        check(storage.read(record.id) == null) { "voice_call_already_exists" }
        commit(record)
    }

    suspend fun get(id: String): OrbisVoiceCallRecord? = locked { read(id) }

    /** Claim before dispatch. Neither a duplicate callback nor process recovery may send again. */
    suspend fun claimIncomingOpening(id: String, conversationId: String, assistantId: String,
        requestId: String, reason: String): OrbisVoiceCallRecord? = locked {
        val before = checkNotNull(read(id)) { "voice_call_not_found" }
        require(before.conversationId == conversationId && before.assistantId == assistantId) { "voice_call_owner_changed" }
        if (before.openingStatus != OrbisVoiceOpeningStatus.NONE) return@locked null
        require(before.status == OrbisVoiceCallStatus.ACTIVE && before.connectedAtMs != null) { "voice_call_not_active" }
        val after = before.copy(openingRequestId = requestId, openingReason = reason,
            openingStatus = OrbisVoiceOpeningStatus.CLAIMED)
        validateUpdate(before, after)
        commit(after)
    }

    suspend fun update(
        id: String,
        transform: (OrbisVoiceCallRecord) -> OrbisVoiceCallRecord,
    ): OrbisVoiceCallRecord = locked {
        val before = checkNotNull(read(id)) { "voice_call_not_found" }
        val after = transform(before)
        validateUpdate(before, after)
        if (after == before) before else commit(after)
    }

    /** Newest first. Filters are explicit so tools can scope reads to the current assistant. */
    suspend fun list(
        conversationId: String? = null,
        assistantId: String? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): List<OrbisVoiceCallRecord> = locked {
        require(limit in 1..1000 && offset >= 0) { "invalid_voice_call_page" }
        records(conversationId, assistantId).drop(offset).take(limit)
    }

    suspend fun search(
        query: String,
        conversationId: String? = null,
        assistantId: String? = null,
        limit: Int = 50,
    ): List<OrbisVoiceCallRecord> = locked {
        require(limit in 1..1000 && query.isNotBlank() && query.length <= 1000) { "invalid_voice_call_search" }
        val needle = query.trim()
        records(conversationId, assistantId).filter { record ->
            record.id.contains(needle, ignoreCase = true) ||
                record.summary?.contains(needle, ignoreCase = true) == true ||
                record.modelTranscript?.contains(needle, ignoreCase = true) == true ||
                record.transcript.any { it.content.contains(needle, ignoreCase = true) }
        }.take(limit)
    }

    /** Invoke once at process startup, before any new call starts; never invent an end time. */
    suspend fun recoverInterrupted(): List<OrbisVoiceCallRecord> = locked {
        records(null, null).mapNotNull { before ->
            val interrupted = before.status == OrbisVoiceCallStatus.CONNECTING ||
                before.status == OrbisVoiceCallStatus.ACTIVE
            val archiveInterrupted = before.archiveStatus == OrbisVoiceArchiveStatus.GENERATING
            val openingInterrupted = before.openingStatus in setOf(OrbisVoiceOpeningStatus.CLAIMED, OrbisVoiceOpeningStatus.GENERATING)
            if (!interrupted && !archiveInterrupted && !openingInterrupted) return@mapNotNull null
            val after = before.copy(
                status = if (interrupted) OrbisVoiceCallStatus.INTERRUPTED else before.status,
                endedAtMs = if (interrupted) null else before.endedAtMs,
                durationMs = if (interrupted) null else before.durationMs,
                archiveStatus = if (archiveInterrupted) OrbisVoiceArchiveStatus.FAILED else before.archiveStatus,
                endReason = if (interrupted) before.endReason ?: "PROCESS_INTERRUPTED" else before.endReason,
                endError = if (interrupted) before.endError ?: "应用中断，通话原文已保留。" else before.endError,
                archiveError = if (archiveInterrupted) before.archiveError ?: "应用中断，归档结果未确认；原文已保留，不会自动重新请求。" else before.archiveError,
                openingStatus = if (openingInterrupted) OrbisVoiceOpeningStatus.UNKNOWN else before.openingStatus,
                openingError = if (openingInterrupted) "应用中断，开场请求结果未确认；不会自动补发。" else before.openingError,
            )
            validateUpdate(before, after)
            commit(after)
        }
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun records(conversationId: String?, assistantId: String?): List<OrbisVoiceCallRecord> =
        storage.ids().map { id -> checkNotNull(read(id)) { "voice_call_record_disappeared" } }
            .filter { (conversationId == null || it.conversationId == conversationId) &&
                (assistantId == null || it.assistantId == assistantId) }
            .sortedWith(compareByDescending<OrbisVoiceCallRecord> { it.startedAtMs }.thenBy { it.id })

    private fun read(id: String): OrbisVoiceCallRecord? {
        validateVoiceCallId(id)
        val value = storage.read(id) ?: return null
        require(value.length <= MAX_CHARS) { "voice_call_archive_too_large" }
        return json.decodeFromString<OrbisVoiceCallRecord>(value).also {
            require(it.id == id) { "voice_call_archive_id_mismatch" }
            validate(it)
        }
    }

    private fun commit(record: OrbisVoiceCallRecord): OrbisVoiceCallRecord {
        validate(record)
        val value = json.encodeToString(record)
        require(value.length <= MAX_CHARS) { "voice_call_archive_too_large" }
        storage.write(record.id, value)
        if (storage.read(record.id) != value) throw IOException("voice_call_archive_verify_failed")
        changes.value += 1
        return record
    }

    private fun validateUpdate(before: OrbisVoiceCallRecord, after: OrbisVoiceCallRecord) {
        require(after.id == before.id && after.conversationId == before.conversationId &&
            after.assistantId == before.assistantId && after.startedAtMs == before.startedAtMs) {
            "voice_call_identity_changed"
        }
        require(after.transcript.size >= before.transcript.size &&
            after.transcript.take(before.transcript.size) == before.transcript) { "voice_call_source_must_be_preserved" }
        require(after.sourceMessageIds.containsAll(before.sourceMessageIds)) { "voice_call_source_ids_must_be_preserved" }
        require(before.sourceNodesJson == null || after.sourceNodesJson != null) { "voice_call_source_nodes_must_be_preserved" }
        require(before.connectedAtMs == null || after.connectedAtMs == before.connectedAtMs) { "voice_call_connection_time_changed" }
        require(before.endedAtMs == null || after.endedAtMs == before.endedAtMs) { "voice_call_end_time_changed" }
        require(before.durationMs == null || after.durationMs == before.durationMs) { "voice_call_duration_changed" }
        require(before.endReason == null || after.endReason == before.endReason) { "voice_call_end_reason_changed" }
        require(before.endReasonText == null || after.endReasonText == before.endReasonText) { "voice_call_end_reason_text_changed" }
        require(before.endError == null || after.endError == before.endError) { "voice_call_end_error_changed" }
        if (before.endReason != null) require(after.endReasonText == before.endReasonText && after.endError == before.endError) {
            "voice_call_end_diagnostics_changed"
        }
        require(before.aiEndRequestedAtMs == null || (after.aiEndRequestedAtMs == before.aiEndRequestedAtMs &&
            after.aiEndReasonText == before.aiEndReasonText)) { "voice_call_ai_end_request_changed" }
        require(before.openingRequestId == null || (after.openingRequestId == before.openingRequestId &&
            after.openingReason == before.openingReason)) { "voice_call_opening_request_changed" }
        if (after.openingStatus != before.openingStatus) require(when (before.openingStatus) {
            OrbisVoiceOpeningStatus.NONE -> after.openingStatus == OrbisVoiceOpeningStatus.CLAIMED
            OrbisVoiceOpeningStatus.CLAIMED -> after.openingStatus != OrbisVoiceOpeningStatus.NONE
            OrbisVoiceOpeningStatus.GENERATING -> after.openingStatus in setOf(OrbisVoiceOpeningStatus.COMPLETED,
                OrbisVoiceOpeningStatus.CANCELLED, OrbisVoiceOpeningStatus.UNKNOWN)
            else -> false
        }) { "voice_call_opening_cannot_reopen" }
        if (before.openingStatus in setOf(OrbisVoiceOpeningStatus.COMPLETED, OrbisVoiceOpeningStatus.CANCELLED, OrbisVoiceOpeningStatus.UNKNOWN)) {
            require(after.openingStatus == before.openingStatus) { "voice_call_opening_is_terminal" }
        }
        require(!before.chatCommitted || after.chatCommitted) { "voice_call_chat_commit_cannot_reopen" }
        if (before.status == OrbisVoiceCallStatus.ENDED || before.status == OrbisVoiceCallStatus.INTERRUPTED) {
            require(after.status == before.status) { "voice_call_cannot_reopen" }
        }
        if (before.archiveStatus == OrbisVoiceArchiveStatus.READY) {
            require(after.archiveStatus == OrbisVoiceArchiveStatus.READY && after.summary == before.summary &&
                after.modelTranscript == before.modelTranscript) { "voice_call_completed_archive_is_immutable" }
        }
        validate(after)
    }

    private fun validate(record: OrbisVoiceCallRecord) {
        validateVoiceCallId(record.id)
        require(!record.chatCommitted || record.archiveStatus == OrbisVoiceArchiveStatus.READY) {
            "voice_call_chat_requires_complete_archive"
        }
        require(record.version == 1) { "unsupported_voice_call_archive" }
        require(record.aiEndRequestedAtMs == null || record.aiEndRequestedAtMs >= 0) { "invalid_ai_end_time" }
        require(record.aiEndReasonText == null || (record.aiEndRequestedAtMs != null && record.aiEndReasonText.length <= 2000)) { "invalid_ai_end_reason" }
        require(record.endReasonText == null || record.endReasonText.length <= 2000) { "invalid_end_reason" }
        require((record.openingStatus == OrbisVoiceOpeningStatus.NONE) == (record.openingRequestId == null)) { "invalid_opening_claim" }
        record.openingRequestId?.let(::validateVoiceCallId)
        require(record.openingReason == null || (record.openingRequestId != null && record.openingReason.length <= 2000)) { "invalid_opening_reason" }
        require(record.conversationId.isNotBlank() && record.assistantId.isNotBlank() && record.startedAtMs >= 0) {
            "invalid_voice_call_identity"
        }
        require(record.connectedAtMs == null || record.connectedAtMs >= record.startedAtMs) { "invalid_voice_call_connection_time" }
        require(record.endedAtMs == null || record.endedAtMs >= (record.connectedAtMs ?: record.startedAtMs)) {
            "invalid_voice_call_end_time"
        }
        require(record.durationMs == null || (record.durationMs >= 0 && record.connectedAtMs != null && record.endedAtMs != null)) {
            "unconfirmed_voice_call_duration"
        }
        if (record.status == OrbisVoiceCallStatus.CONNECTING || record.status == OrbisVoiceCallStatus.ACTIVE) {
            require(record.endedAtMs == null && record.durationMs == null) { "active_voice_call_already_ended" }
        }
        if (record.status == OrbisVoiceCallStatus.ACTIVE) require(record.connectedAtMs != null) { "voice_call_not_connected" }
        require(record.transcript.map { it.id }.distinct().size == record.transcript.size) { "duplicate_voice_transcript_id" }
        require(record.transcript.all { it.id.isNotBlank() && it.role.isNotBlank() && it.timestampMs >= 0 }) {
            "invalid_voice_transcript_entry"
        }
        require(record.sourceMessageIds.all { it.isNotBlank() } &&
            record.sourceMessageIds.distinct().size == record.sourceMessageIds.size) { "invalid_voice_call_source_ids" }
        if (record.archiveStatus == OrbisVoiceArchiveStatus.READY) {
            require(record.status == OrbisVoiceCallStatus.ENDED || record.status == OrbisVoiceCallStatus.INTERRUPTED) {
                "active_voice_call_cannot_be_archived"
            }
            require(isUsableVoiceArchiveText(record.summary) && isUsableVoiceArchiveText(record.modelTranscript)) {
                "voice_call_archive_incomplete"
            }
        }
    }

    private companion object {
        const val MAX_CHARS = 16 * 1024 * 1024
        val locks = ConcurrentHashMap<String, Mutex>()
        val revisions = ConcurrentHashMap<String, MutableStateFlow<Long>>()
    }
}

internal fun validateVoiceCallId(id: String) {
    require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "invalid_voice_call_id" }
}
