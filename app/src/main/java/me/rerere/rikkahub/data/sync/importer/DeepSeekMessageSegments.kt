package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant

/** Database rows stay small, without imposing a limit on the original message.
 * Segments retain role, timestamps, source metadata and deterministic identities.
 * No text is trimmed or replaced, including whitespace and surrogate pairs.
 */
internal fun partitionDeepSeekMessage(message: UIMessage, chat: String, leaf: String, source: String,
    checkCancelled: () -> Unit): List<MessageNode> {
    fun fits(parts: List<UIMessagePart>) = JsonInstant.encodeToString(listOf(message.copy(parts = parts)))
        .toByteArray(Charsets.UTF_8).size <= DeepSeekChatImporter.MAX_NODE_JSON_BYTES
    val nodeId = deepSeekImportId("node", chat, leaf, source)
    if (fits(message.parts)) return listOf(MessageNode(id = nodeId, messages = listOf(message)))
    fun chunks(text: String): Sequence<String> = sequence {
        var start = 0
        while (start < text.length) {
            checkCancelled()
            var end = minOf(start + 24 * 1024, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            yield(text.substring(start, end)); start = end
        }
        if (text.isEmpty()) yield("")
    }
    val groups = mutableListOf<List<UIMessagePart>>()
    var current = mutableListOf<UIMessagePart>()
    for (part in message.parts) {
        val pieces = when (part) {
            is UIMessagePart.Text -> chunks(part.text).map { part.copy(text = it) }
            is UIMessagePart.Reasoning -> chunks(part.reasoning).map { part.copy(reasoning = it) }
            else -> sequenceOf(part)
        }
        for (piece in pieces) {
            checkCancelled()
            if (!fits(current + piece)) {
                if (current.isNotEmpty()) { groups += current; current = mutableListOf() }
                // Non-text attachments and source metadata must still fit one physical DB row.
                if (!fits(listOf(piece))) throw ArchiveReadException(ArchiveFailure.NODE_LIMIT)
            }
            current += piece
        }
    }
    if (current.isNotEmpty()) groups += current
    return groups.mapIndexed { index, parts ->
        val segment = message.copy(id = if (index == 0) message.id else
            deepSeekImportId("message-segment", chat, leaf, source, index.toString()), parts = parts)
        MessageNode(id = if (index == 0) nodeId else
            deepSeekImportId("node-segment", chat, leaf, source, index.toString()), messages = listOf(segment))
    }
}
