package me.rerere.rikkahub.data.orbis

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage

/** Only inspect the selected turn owned by this event, never another turn's reply. */
internal fun orbisEventCompletionState(messages: List<UIMessage>, recordId: String, hasError: Boolean): String {
    if (hasError) return "unknown"
    val index = messages.indexOfFirst { it.orbisEvent?.recordId == recordId }
    if (index < 0) return "unknown"
    val turn = messages.drop(index + 1).takeWhile { it.role != MessageRole.USER }
    if (turn.any { it.parts.any { part -> part is UIMessagePart.Tool && part.isPending } }) return "pending_tool"
    return if (turn.any { it.role == MessageRole.ASSISTANT && it.finishedAt != null && !it.parts.isEmptyInputMessage() }) "replied" else "unknown"
}
