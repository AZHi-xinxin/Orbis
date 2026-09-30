package me.rerere.rikkahub.data.orbis.voice

import kotlinx.serialization.Serializable

@Serializable
enum class OrbisVoiceCallStatus { CONNECTING, ACTIVE, ENDED, INTERRUPTED }

/** Hanging up and completing the same assistant's written archive are separate transactions. */
@Serializable
enum class OrbisVoiceArchiveStatus { PENDING, GENERATING, READY, FAILED }

/** A durable one-shot claim: an unknown opening is never automatically replayed. */
@Serializable
enum class OrbisVoiceOpeningStatus { NONE, CLAIMED, GENERATING, COMPLETED, CANCELLED, UNKNOWN }

@Serializable
data class OrbisVoiceTranscriptEntry(
    val id: String,
    val role: String,
    val content: String,
    val timestampMs: Long,
    val messageId: String? = null,
)

@Serializable
data class OrbisVoiceCallRecord(
    val id: String,
    val conversationId: String,
    val assistantId: String,
    val startedAtMs: Long,
    val modelId: String? = null,
    val connectedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val durationMs: Long? = null,
    val status: OrbisVoiceCallStatus = OrbisVoiceCallStatus.CONNECTING,
    val archiveStatus: OrbisVoiceArchiveStatus = OrbisVoiceArchiveStatus.PENDING,
    val summary: String? = null,
    /** The assistant's written account, never substituted for the captured source below. */
    val modelTranscript: String? = null,
    val transcript: List<OrbisVoiceTranscriptEntry> = emptyList(),
    val sourceMessageIds: List<String> = emptyList(),
    /** Exact serialized message nodes, including tools and alternatives, owned by ChatService. */
    val sourceNodesJson: String? = null,
    /** Durable archive and chat-page commit are separate; recovery never regenerates a READY archive. */
    val chatCommitted: Boolean = false,
    /** Legacy combined error, retained when reading old records; new writes use separate fields. */
    val error: String? = null,
    val endReason: String? = null,
    val endReasonText: String? = null,
    val endError: String? = null,
    val archiveError: String? = null,
    val aiEndRequestedAtMs: Long? = null,
    val aiEndReasonText: String? = null,
    val openingRequestId: String? = null,
    val openingReason: String? = null,
    val openingStatus: OrbisVoiceOpeningStatus = OrbisVoiceOpeningStatus.NONE,
    val openingError: String? = null,
    val version: Int = 1,
)

/** Old records have no typed error; do not retrospectively claim a known end cause. */
fun OrbisVoiceCallRecord.archiveErrorForDisplay(): String? = archiveError ?: error?.takeIf {
    archiveStatus == OrbisVoiceArchiveStatus.FAILED || (archiveStatus == OrbisVoiceArchiveStatus.READY && !chatCommitted)
}

@Serializable
data class OrbisVoiceModelArchive(val summary: String, val transcript: String)
