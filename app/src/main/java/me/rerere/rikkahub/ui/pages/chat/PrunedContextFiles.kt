package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningDisplayProjection
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.isOrbisVoiceNote
import me.rerere.rikkahub.data.orbis.privateroom.isPotentialPrivateRoomToolPart

/**
 * File-only view of hidden historical tool results. Original messages remain untouched; this
 * cannot execute a tool or substitute the assistant's current workspace for its historical one.
 * The attachment UI must still validate ownership/existence when the human opens a file.
 */
internal fun prunedContextFiles(
    node: MessageNode,
    projection: ContextPruningDisplayProjection?,
): List<OrbisCallFile> {
    val hidden = projection?.hiddenToolIndexes?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val message = node.currentMessage
    val tools = message.parts.filterIndexed { index, part -> index in hidden && part is UIMessagePart.Tool &&
        !part.isPotentialPrivateRoomToolPart() }
    if (tools.isEmpty()) return emptyList()
    fun fileNode(parts: List<UIMessagePart>) = node.copy(
        messages = listOf(message.copy(parts = parts)),
        selectIndex = 0,
    )
    // Top-level documents already have their normal message chip. Do not add a second chip for
    // an identical structured attachment also present in a hidden tool's output.
    val directDocuments = message.parts.filterIsInstance<UIMessagePart.Document>()
    val alreadyVisible = if (directDocuments.isEmpty()) emptySet() else
        orbisCallFiles(listOf(fileNode(directDocuments))).map { it.key }.toSet()
    return orbisCallFiles(listOf(fileNode(tools))).filterNot { it.key in alreadyVisible }
}

/** Preserve actual media outputs, never the old tool body or another execution/approval entry. */
internal fun prunedContextMedia(
    node: MessageNode,
    projection: ContextPruningDisplayProjection?,
): List<UIMessagePart> {
    val hidden = projection?.hiddenToolIndexes?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val original = node.currentMessage.parts
    return original.flatMapIndexed { index, part ->
        if (index !in hidden || part !is UIMessagePart.Tool || part.isPotentialPrivateRoomToolPart() || !part.isExecuted ||
            part.approvalState is ToolApprovalState.Denied) return@flatMapIndexed emptyList()
        part.output.filter { output ->
            when (output) {
                is UIMessagePart.Image, is UIMessagePart.Video -> true
                // Voice notes already have independent rows derived from the original parts.
                is UIMessagePart.Audio -> !output.isOrbisVoiceNote()
                else -> false
            }
        }
    }.distinct().filterNot { it in original }
}
