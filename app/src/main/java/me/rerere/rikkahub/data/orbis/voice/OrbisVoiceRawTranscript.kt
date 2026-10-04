package me.rerere.rikkahub.data.orbis.voice

import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.orbis.privateroom.privateRoomPublicReplyText
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest

data class OrbisVoiceTranscriptView(
    val entries: List<OrbisVoiceTranscriptEntry>,
    val sourceUnavailable: Boolean,
)

const val VOICE_CALL_SOURCE_UNAVAILABLE_MESSAGE =
    "部分原始消息片段暂时无法读取；这里只显示仍可读取的记录，不代表完整通话。原文件保留，未自动修复或归档。"

/** Display only: a damaged optional source must not crash the history page or erase captured ASR. */
fun voiceCallTranscriptView(record: OrbisVoiceCallRecord): OrbisVoiceTranscriptView = try {
    OrbisVoiceTranscriptView(strictVoiceCallReadableTranscript(record), sourceUnavailable = false)
} catch (_: VoiceArchiveFailure) {
    // Captured entries are spoken Text/accepted ASR, not raw tool parts. Preserve that public
    // text while clearly marking the missing source; do not invent a successful archive.
    OrbisVoiceTranscriptView(record.transcript.toList(), sourceUnavailable = true)
}

/** UI compatibility projection. Never use this best-effort view for an archive request or digest. */
fun voiceCallReadableTranscript(record: OrbisVoiceCallRecord): List<OrbisVoiceTranscriptEntry> =
    voiceCallTranscriptView(record).entries

/** Fixed safe failure only; decoder exceptions may include private source fragments. */
private fun strictVoiceCallSourceNodes(record: OrbisVoiceCallRecord): List<MessageNode> = try {
    val nodes = record.sourceNodesJson?.let { JsonInstant.decodeFromString<List<MessageNode>>(it) }.orEmpty()
    require(nodes.all { it.messages.isNotEmpty() && it.selectIndex in it.messages.indices })
    require(nodes.map { it.id }.distinct().size == nodes.size)
    nodes
} catch (_: Exception) {
    throw VoiceArchiveFailure("archive_source_unreadable")
}

/** Strict archive projection: a missing/corrupt fragment never becomes a successful partial archive. */
internal fun strictVoiceCallReadableTranscript(record: OrbisVoiceCallRecord): List<OrbisVoiceTranscriptEntry> {
    val nodes = strictVoiceCallSourceNodes(record)
    val entries = record.transcript.toMutableList()
    for (node in nodes) {
        val message = node.currentMessage
        if (message.orbisVoiceCallId != record.id || message.orbisVoiceCallKind !in setOf("turn", "opening") ||
            message.role !in setOf(MessageRole.USER, MessageRole.ASSISTANT)) continue
        // Only real Text after filtering; think-only/tool-only visual notices are not utterances.
        val readableText = message.privateRoomPublicReplyText() ?: continue
        val index = entries.indexOfFirst { it.messageId == message.id.toString() }
        // Accepted ASR is stored before the host CALL_MODE wrapper is added. Keep its captured
        // corrected content AND original-ASR audit; never re-run current name rules on history.
        if (index >= 0 && message.role == MessageRole.USER) continue
        val entry = OrbisVoiceTranscriptEntry(message.id.toString(), message.role.name, readableText,
            entries.getOrNull(index)?.timestampMs ?: record.startedAtMs, message.id.toString())
        if (index >= 0) entries[index] = entry else entries.add(entry)
    }
    return entries
}

/** Exact private nodes are durable independently of summary generation and of chat-page folding. */
internal fun captureVoiceCallSource(record: OrbisVoiceCallRecord, conversation: Conversation,
    finished: Boolean, capturedAtMs: Long, authoritativeLiveSnapshot: Boolean = true): OrbisVoiceCallRecord {
    require(record.conversationId == conversation.id.toString() && record.assistantId == conversation.assistantId.toString()) {
        "voice_call_source_owner_changed"
    }
    // Do not merge a fallback into storage: an unreadable original must remain byte-for-byte intact.
    val old = strictVoiceCallSourceNodes(record)
    val owned = conversation.messageNodes.filter { node ->
        node.messages.any { it.orbisVoiceCallId == record.id && it.orbisVoiceCallKind != "summary" }
    }
    val merged = old.associateBy { it.id }.toMutableMap()
    owned.forEach { node ->
        // On restart Room may lag the independently durable streaming tail. Never replace it with stale Room.
        if (authoritativeLiveSnapshot || node.id !in merged) merged[node.id] = node
    }
    val nodes = merged.values.toList()
    var result = record.copy(sourceNodesJson = JsonInstant.encodeToString(nodes),
        sourceMessageIds = (record.sourceMessageIds + nodes.flatMap { it.messages }.map { it.id.toString() }).distinct())
    if (finished) {
        val seen = record.transcript.map { it.messageId }.toSet()
        result = result.copy(transcript = record.transcript + strictVoiceCallReadableTranscript(result)
            .filter { it.messageId !in seen }.map { it.copy(timestampMs = capturedAtMs) })
    }
    if (!record.chatCommitted && record.archiveStatus == OrbisVoiceArchiveStatus.READY &&
        voiceArchiveSourceDigest(record) != voiceArchiveSourceDigest(result)) {
        // Retain the previous summary, but it no longer represents the full latest source.
        result = result.copy(archiveStatus = OrbisVoiceArchiveStatus.PENDING,
            archiveFailureCode = "archive_source_changed", archiveError = voiceArchiveFailureMessage("archive_source_changed"))
    }
    return result
}

internal fun voiceArchiveSourceDigest(record: OrbisVoiceCallRecord): String = MessageDigest.getInstance("SHA-256")
    .digest(JsonInstant.encodeToString(strictVoiceCallReadableTranscript(record)).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
