package me.rerere.rikkahub.data.orbis.voice

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Request-only acoustics for the currently owned live utterance; historical calls add nothing. */
internal suspend fun projectActiveVoiceCallAcoustics(
    messages: List<UIMessage>,
    assistantId: String,
    readRecord: suspend (String) -> OrbisVoiceCallRecord?,
    readAcousticNote: suspend (String, String) -> String?,
): List<UIMessage> {
    val index = messages.indexOfLast { it.role == MessageRole.USER }
    if (index < 0) return messages
    val utterance = messages[index]
    if (utterance.orbisVoiceCallKind != "turn") return messages
    val callId = utterance.orbisVoiceCallId ?: return messages
    val record = readRecord(callId) ?: return messages
    if (record.id != callId || record.assistantId != assistantId ||
        record.status != OrbisVoiceCallStatus.ACTIVE) return messages
    // The runtime rechecks live ownership when claiming a note. This does not start a call,
    // replay an END event, or change a retained transcript if its call has already ended.
    val note = readAcousticNote(callId, utterance.id.toString()) ?: return messages
    val part = UIMessagePart.Text(note)
    if (part in utterance.parts) return messages
    return messages.mapIndexed { current, message ->
        if (current == index) message.copy(parts = message.parts + part) else message
    }
}
