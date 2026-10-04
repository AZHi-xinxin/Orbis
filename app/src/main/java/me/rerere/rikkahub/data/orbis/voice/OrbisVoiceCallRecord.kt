package me.rerere.rikkahub.data.orbis.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
enum class OrbisVoiceCallStatus { CONNECTING, ACTIVE, ENDED, INTERRUPTED }

/** Saving raw source and completing the assistant-first summary are independent. */
@Serializable
enum class OrbisVoiceArchiveStatus { PENDING, GENERATING, READY, FAILED }

/** Host-written provenance, never taken from a model's response or historical text. */
@Serializable
enum class OrbisVoiceArchiveAuthor { ASSISTANT, FALLBACK, ASSISTANT_TOOL }

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
    /** Exact accepted ASR before name correction. Never recomputed using later settings. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val originalTranscript: String? = null,
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
    /** A model's written account, never substituted for the captured source below. */
    val modelTranscript: String? = null,
    val transcript: List<OrbisVoiceTranscriptEntry> = emptyList(),
    val sourceMessageIds: List<String> = emptyList(),
    /** Exact serialized message nodes, including tools and alternatives, owned by ChatService. */
    val sourceNodesJson: String? = null,
    /** Legacy storage replacement flag. New UI cards/independent archives do not need it. */
    val chatCommitted: Boolean = false,
    /** Legacy combined error, retained when reading old records; new writes use separate fields. */
    val error: String? = null,
    val endReason: String? = null,
    val endReasonText: String? = null,
    val endError: String? = null,
    val archiveError: String? = null,
    /** Count explicitly dispatched archive requests, not local submit-tool receipts. */
    val archiveRequestCount: Int = 0,
    val archiveLastModelId: String? = null,
    /** Durable request ownership: old/late attempts cannot overwrite the winner. */
    val archiveAttemptId: String? = null,
    val archiveAttemptSourceDigest: String? = null,
    val archiveAttemptAuthor: OrbisVoiceArchiveAuthor? = null,
    /** Set only on successful publication. Null means legacy provenance is unknown. */
    val archiveAuthor: OrbisVoiceArchiveAuthor? = null,
    /** Caller idempotency key for a local assistant submit, not a claimed provider tool_call_id. */
    val archiveReceiptId: String? = null,
    /** Closed host code; provider response bodies and private error details do not belong here. */
    val archiveFailureCode: String? = null,
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
    archiveStatus == OrbisVoiceArchiveStatus.FAILED
}

@Serializable
data class OrbisVoiceModelArchive(val summary: String, val transcript: String)

fun OrbisVoiceCallRecord.archiveAuthorLabel(): String = when (archiveAuthor) {
    OrbisVoiceArchiveAuthor.ASSISTANT -> "本助手整理"
    OrbisVoiceArchiveAuthor.FALLBACK -> "外部模型兜底整理"
    OrbisVoiceArchiveAuthor.ASSISTANT_TOOL -> "本助手补写"
    null -> "旧记录未标注整理来源"
}
