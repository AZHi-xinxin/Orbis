package me.rerere.rikkahub.data.orbis.privateroom

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Inputs for public titles/suggestions/summaries only, never the active assistant context. */
fun privateRoomPublicSummaryInput(
    messages: List<UIMessage>,
    maxMessages: Int = Int.MAX_VALUE,
    maxLength: Int = Int.MAX_VALUE,
): String = messages.mapNotNull { message ->
    message.privateRoomPublicReplyText()?.let { text -> message.copy(parts = listOf(UIMessagePart.Text(text))) }
}
    .takeLast(maxMessages)
    .joinToString("\n\n") { it.summaryAsText(maxLength = maxLength) }

/** Speak the assistant's public Text even in a private-tool turn, never tools/reasoning or a generated placeholder. */
fun UIMessage.privateRoomAutomaticSpeechText(): String? =
    takeIf { role == MessageRole.ASSISTANT }?.privateRoomPublicReplyText()

/** No visual placeholder, Reasoning or nested tool output becomes generated prose or speech. */
fun UIMessage.privateRoomPublicReplyText(): String? {
    val visibleParts = if (hasPrivateRoomToolContent()) privateRoomPublicParts(parts, includePlaceholder = false) else parts
    return visibleParts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        .takeIf { it.isNotBlank() }
}
