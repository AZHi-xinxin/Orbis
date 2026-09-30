package me.rerere.rikkahub.data.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.DeletedToolRecord
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid
import java.security.MessageDigest

/** An exact selected message/tool identity, not a UI-supplied replacement snapshot. */
data class OrbisToolRecordEdit(val messageId: Uuid, val toolCallId: String, val restore: Boolean)

internal fun UIMessage.withToolRecordEdit(edit: OrbisToolRecordEdit, now: LocalDateTime): UIMessage {
    check(id == edit.messageId && role == MessageRole.ASSISTANT && edit.toolCallId.isNotBlank()) {
        "工具记录所属回复已变更，请刷新后再操作。"
    }
    check(parts.filterIsInstance<UIMessagePart.Tool>().none { !it.isExecuted }) {
        "这条回复仍有未结束的工具，请等工具结束后再操作。"
    }
    check(deletedToolRecords.map { it.tool.toolCallId }.distinct().size == deletedToolRecords.size) {
        "工具撤销记录不完整，未修改对话。"
    }
    val reconstructed = reconstructToolRecordParts()
    val contextHash = toolUndoContextHash(reconstructed)
    check(deletedToolRecords.all { it.undoContextHash.isEmpty() || it.undoContextHash == contextHash }) {
        "这条回复的内容或顺序已更改，无法安全恢复原工具位置；未修改对话。"
    }
    val matches = reconstructed.withIndex().filter { (_, part) ->
        part is UIMessagePart.Tool && part.toolCallId == edit.toolCallId
    }
    check(matches.size == 1) { "未找到唯一的工具记录，未修改对话。" }
    val target = matches.single()
    val existing = deletedToolRecords.singleOrNull { it.tool.toolCallId == edit.toolCallId }
    if (edit.restore) check(existing != null) { "这条工具记录未被删除。" }
    else check(existing == null) { "这条工具记录已经删除。" }
    val nextDeleted = if (edit.restore) {
        deletedToolRecords.filterNot { it.tool.toolCallId == edit.toolCallId }
    } else {
        deletedToolRecords + DeletedToolRecord(target.value as UIMessagePart.Tool, target.index, now, contextHash)
    }
    val excluded = nextDeleted.map { it.tool.toolCallId }.toSet()
    return copy(
        parts = reconstructed.filterNot { it is UIMessagePart.Tool && it.toolCallId in excluded },
        deletedToolRecords = nextDeleted,
        toolRecordRevision = Math.addExact(toolRecordRevision, 1L),
        toolRecordsUpdatedAt = now,
    )
}

private fun UIMessage.reconstructToolRecordParts(): List<UIMessagePart> {
    val reconstructed = parts.toMutableList()
    deletedToolRecords.sortedBy { it.originalPartIndex }.forEach { record ->
        check(record.originalPartIndex in 0..reconstructed.size) { "回复内容已变更，无法安全恢复工具位置。" }
        check(reconstructed.none { it is UIMessagePart.Tool && it.toolCallId == record.tool.toolCallId }) {
            "工具记录已变更，未修改对话。"
        }
        reconstructed.add(record.originalPartIndex, record.tool)
    }
    return reconstructed
}

// Dynamic defaults (notably reasoning timestamps) must always be encoded: otherwise a field can
// disappear only when encoding happens to land in the same clock tick as its default expression.
private val toolUndoHashJson = Json { encodeDefaults = true }
private fun toolUndoContextHash(parts: List<UIMessagePart>): String = MessageDigest.getInstance("SHA-256")
    .digest(toolUndoHashJson.encodeToString(parts).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** A fork changes attachment URLs by design, so refresh only its local integrity anchors. */
internal fun UIMessage.reanchorDeletedToolRecords(): UIMessage = if (deletedToolRecords.isEmpty()) this else {
    val hash = toolUndoContextHash(reconstructToolRecordParts())
    copy(deletedToolRecords = deletedToolRecords.map { it.copy(undoContextHash = hash) })
}

/** Enforce branch ownership; hidden alternatives are never edited through the visible action. */
internal fun Conversation.applyToolRecordEdit(edit: OrbisToolRecordEdit, now: LocalDateTime): Conversation {
    val node = messageNodes.singleOrNull { it.currentMessage.id == edit.messageId }
        ?: error("这条回复已切换或不存在，请刷新后再操作。")
    val edited = node.currentMessage.withToolRecordEdit(edit, now)
    return copy(messageNodes = messageNodes.map { current ->
        if (current.id != node.id) current else current.copy(messages = current.messages.map {
            if (it.id == edit.messageId) edited else it
        })
    })
}

/**
 * A delayed full-state save may not undo a dedicated deletion/restoration. Retain its unrelated
 * message metadata (translation, usage, etc.), but reject conflicting prose edits for a refresh.
 * Same-revision saves also cannot smuggle a deleted call back into parts.
 */
internal fun preserveToolRecordEdits(incoming: MessageNode, committed: MessageNode): MessageNode {
    if (incoming.id != committed.id) return incoming
    if (incoming === committed || incoming.messages === committed.messages ||
        committed.messages.none { it.toolRecordRevision != 0L }) return incoming
    val known = committed.messages.associateBy { it.id }
    val merged = incoming.messages.mapPreservingIdentity { candidate ->
        val previous = known[candidate.id] ?: return@mapPreservingIdentity candidate
        if (candidate === previous || previous.toolRecordRevision == 0L) return@mapPreservingIdentity candidate
        check(candidate.role == previous.role && candidate.toolRecordRevision <= previous.toolRecordRevision) {
            "工具记录已变更，请刷新后再操作。"
        }
        val chosenParts = if (candidate.toolRecordRevision < previous.toolRecordRevision) {
            check(candidate.parts.filterNot { it is UIMessagePart.Tool } == previous.parts.filterNot { it is UIMessagePart.Tool }) {
                "回复和工具记录同时发生更改，请刷新后再编辑。"
            }
            previous.parts
        } else {
            val deletedIds = previous.deletedToolRecords.map { it.tool.toolCallId }.toSet()
            if (deletedIds.isEmpty() || candidate.parts.none {
                    it is UIMessagePart.Tool && it.toolCallId in deletedIds
                }) candidate.parts
            else candidate.parts.filterNot { it is UIMessagePart.Tool && it.toolCallId in deletedIds }
        }
        if (chosenParts === candidate.parts && candidate.deletedToolRecords === previous.deletedToolRecords &&
            candidate.toolRecordRevision == previous.toolRecordRevision &&
            candidate.toolRecordsUpdatedAt == previous.toolRecordsUpdatedAt) candidate
        else candidate.copy(parts = chosenParts, deletedToolRecords = previous.deletedToolRecords,
            toolRecordRevision = previous.toolRecordRevision, toolRecordsUpdatedAt = previous.toolRecordsUpdatedAt)
    }
    return if (merged === incoming.messages) incoming else incoming.copy(messages = merged)
}

internal fun withCommittedToolRecordEdits(snapshot: Conversation, current: Conversation): Conversation {
    if (snapshot.id != current.id || snapshot.assistantId != current.assistantId) return snapshot
    if (snapshot === current || snapshot.messageNodes === current.messageNodes ||
        current.messageNodes.none { node -> node.messages.any { it.toolRecordRevision != 0L } }) return snapshot
    val known = current.messageNodes.associateBy { it.id }
    val merged = snapshot.messageNodes.mapPreservingIdentity { node ->
        known[node.id]?.let { preserveToolRecordEdits(node, it) } ?: node
    }
    return if (merged === snapshot.messageNodes) snapshot else snapshot.copy(messageNodes = merged)
}
