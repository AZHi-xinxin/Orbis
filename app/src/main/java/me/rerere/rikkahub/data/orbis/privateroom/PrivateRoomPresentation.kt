package me.rerere.rikkahub.data.orbis.privateroom

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import me.rerere.rikkahub.data.model.MessageNode

/** Exact local tools only. An unrelated MCP tool or prose containing these words is not private. */
fun isPrivateRoomToolName(name: String): Boolean = name in PRIVATE_ROOM_TOOL_NAMES

/** A streaming local tool name may not yet be complete. Never match arbitrary MCP suffixes. */
fun isPotentialPrivateRoomToolName(name: String): Boolean =
    name.isEmpty() || PRIVATE_ROOM_TOOL_NAMES.any { it.startsWith(name) }

private val PRIVATE_ROOM_TOOL_NAMES = setOf(
    "orbis_private_room_visit", "orbis_private_room_list", "orbis_private_room_read",
    "orbis_private_room_write", "orbis_private_room_requests", "orbis_private_room_decide",
    "orbis_private_room_revoke",
)

const val PRIVATE_ROOM_CONTENT_HIDDEN = "隐私室操作记录与思考已隐藏"
const val LOCAL_MEMORY_CONTENT_HIDDEN = "本机记忆操作记录与相关思考已隐藏"

/** Human presentation only: local memory is NOT a private-room execution or authorization scope. */
fun isLocalMemoryToolName(name: String): Boolean = name == "orbis_memory"

/** Streamed prefixes (including an unnamed call) hide records until resolved; MCP suffixes never match. */
fun isPotentialLocalMemoryToolName(name: String): Boolean = "orbis_memory".startsWith(name)

@Suppress("DEPRECATION")
fun UIMessagePart.isLocalMemoryToolPart(): Boolean = when (this) {
    is UIMessagePart.Tool -> isPotentialLocalMemoryToolName(toolName)
    is UIMessagePart.ToolCall -> isPotentialLocalMemoryToolName(toolName)
    is UIMessagePart.ToolResult -> isPotentialLocalMemoryToolName(toolName)
    else -> false
}

@Suppress("DEPRECATION")
fun UIMessagePart.isPrivateRoomToolPart(): Boolean = when (this) {
    is UIMessagePart.Tool -> isPrivateRoomToolName(toolName)
    is UIMessagePart.ToolCall -> isPrivateRoomToolName(toolName)
    is UIMessagePart.ToolResult -> isPrivateRoomToolName(toolName)
    else -> false
}

@Suppress("DEPRECATION")
fun UIMessagePart.isPotentialPrivateRoomToolPart(): Boolean = when (this) {
    is UIMessagePart.Tool -> isPotentialPrivateRoomToolName(toolName)
    is UIMessagePart.ToolCall -> isPotentialPrivateRoomToolName(toolName)
    is UIMessagePart.ToolResult -> isPotentialPrivateRoomToolName(toolName)
    else -> false
}

/** Human-only boundary, including local memory and undo history. Never use for tool authorization. */
fun UIMessage.hasPrivateRoomToolContent(): Boolean = privateRoomContentHidden ||
    (privateRoomPendingPresentation && parts.any { it.isPotentialPrivateRoomToolPart() }) ||
    parts.any { it.isPrivateRoomToolPart() || it.isLocalMemoryToolPart() } ||
    deletedToolRecords.any { isPrivateRoomToolName(it.tool.toolName) || it.tool.isLocalMemoryToolPart() }

/**
 * Human-facing projection ONLY. Never persist this value, pass it to a provider, or use it to edit
 * the original message. Even unfinished/malformed tool arguments require no parsing to hide.
 * Text is the assistant's public reply, including errors and instructions, and must remain visible.
 * The original flags identify sensitive operation records, not a prohibition on showing the reply.
 * Clear flags only on this disposable presentation so downstream renderers do not hide it again.
 * This is a display boundary, not an encryption/secrecy promise; full original records remain stored.
 */
fun UIMessage.privateRoomSafePresentation(): UIMessage = if (!hasPrivateRoomToolContent()) this else copy(
    privateRoomContentHidden = false,
    privateRoomPendingPresentation = false,
    parts = projectPublicParts(parts, includePlaceholder = true,
        reasoningHiddenFromIndex = privateReasoningBoundary(), source = this),
    annotations = emptyList(),
    translation = null,
    orbisEvent = null,
    orbisQuote = null,
    deletedToolRecords = deletedToolRecords.filterNot {
        isPotentialPrivateRoomToolName(it.tool.toolName) || it.tool.isLocalMemoryToolPart()
    },
)

/**
 * Private tool records are hidden; ordinary tools and top-level public content remain visible.
 * Only reasoning at/after the first private tool is hidden. Text containing a leading think tag
 * is split with the existing transformer, so Text-only consumers never receive thinking text.
 * Never promote nested private output or parse partial tool arguments.
 * This helper is only for a message already identified as a private/pending presentation.
 */
fun privateRoomPublicParts(parts: List<UIMessagePart>, includePlaceholder: Boolean = true): List<UIMessagePart> =
    projectPublicParts(parts, includePlaceholder,
        parts.indexOfFirst { it.isPotentialPrivateRoomToolPart() || it.isLocalMemoryToolPart() }
            .takeIf { it >= 0 } ?: 0, source = null)

private fun UIMessage.privateReasoningBoundary(): Int {
    val first = parts.indexOfFirst { it.isPrivateRoomToolPart() || it.isLocalMemoryToolPart() ||
        (privateRoomPendingPresentation && it.isPotentialPrivateRoomToolPart()) }
    if (first >= 0) return first
    // A marked continuation has already entered the room. A deleted private tool has no safe
    // current-list position: do not guess its conceptual undo index in potentially edited parts.
    return if (privateRoomContentHidden || deletedToolRecords.any {
        isPrivateRoomToolName(it.tool.toolName) || it.tool.isLocalMemoryToolPart()
    }) 0
        else parts.size
}

private fun projectPublicParts(
    parts: List<UIMessagePart>,
    includePlaceholder: Boolean,
    reasoningHiddenFromIndex: Int,
    source: UIMessage?,
): List<UIMessagePart> = parts.flatMapIndexed { index, part ->
    if (part.isPotentialPrivateRoomToolPart() || part.isLocalMemoryToolPart()) return@flatMapIndexed emptyList()
    when (part) {
        is UIMessagePart.Text -> publicReplyParts(part, source).filterNot {
            it is UIMessagePart.Reasoning && index >= reasoningHiddenFromIndex
        }
        is UIMessagePart.Reasoning -> if (index < reasoningHiddenFromIndex) listOf(part) else emptyList()
        is UIMessagePart.Image -> listOf(part.copy(metadata = null))
        is UIMessagePart.Video -> listOf(part.copy(metadata = null))
        is UIMessagePart.Audio -> listOf(part.copy(metadata = null))
        is UIMessagePart.Document -> listOf(part.copy(metadata = null))
        else -> listOf(part) // Ordinary tools remain visible, both before and after the room call.
    }
}.let { visible ->
    if (includePlaceholder && (visible.isEmpty() || visible.all { it is UIMessagePart.Text && it.text.isBlank() }))
        listOf(UIMessagePart.Text(hiddenOperationNotice(parts, source))) else visible
}

private fun hiddenOperationNotice(parts: List<UIMessagePart>, source: UIMessage?): String {
    val localMemory = parts.any { it.isLocalMemoryToolPart() } ||
        source?.deletedToolRecords?.any { it.tool.isLocalMemoryToolPart() } == true
    val privateRoom = source?.privateRoomContentHidden == true || parts.any { it.isPrivateRoomToolPart() } ||
        source?.deletedToolRecords?.any { isPrivateRoomToolName(it.tool.toolName) } == true
    return when {
        localMemory && privateRoom -> "本机记忆与隐私室操作记录及相关思考已隐藏"
        localMemory -> LOCAL_MEMORY_CONTENT_HIDDEN
        else -> PRIVATE_ROOM_CONTENT_HIDDEN
    }
}

private fun publicReplyParts(part: UIMessagePart.Text, source: UIMessage?): List<UIMessagePart> {
    // Reuse the provider's existing leading-think interpretation, including an unclosed block.
    // Isolate each Text so an existing Reasoning part cannot short-circuit that transformer.
    val createdAt = source?.createdAt ?: LocalDateTime(1970, 1, 1, 0, 0)
    val transformed = listOf(UIMessage(role = MessageRole.ASSISTANT, createdAt = createdAt,
        parts = listOf(part.copy(metadata = null))))
        .transformThinkTags(now = (source?.finishedAt ?: createdAt).toInstant(TimeZone.currentSystemDefault()),
            generationFinished = source?.finishedAt != null).single()
    val opening = part.text.trimStart()
    // A streamed partial opening delimiter is not yet public prose; no parameter/body is parsed.
    val partialThinkOpening = opening.isNotEmpty() && opening.length < "<think>".length && "<think>".startsWith(opening)
    return if (partialThinkOpening) listOf(UIMessagePart.Text("")) else transformed.parts
}

/** All branches, not just the selected branch; this projection must never be written back. */
fun MessageNode.privateRoomSafePresentation(): MessageNode {
    if (messages.none { it.hasPrivateRoomToolContent() }) return this
    return copy(messages = messages.map { it.privateRoomSafePresentation() })
}
