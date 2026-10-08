package me.rerere.rikkahub.data.orbis.voice

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Request-only projection. The old implementation appended an END as a fresh USER message on
 * every wake, even when the call was far back in history. That both displaced the real input
 * and made an unchanged lifecycle fact look like another utterance.
 *
 * Keep the original message identity, role, parts and position. A scoped annotation belongs to
 * the last call-owned message instead; it is not a new END event or a higher-priority instruction.
 * No archive/queue/history write or tool operation is performed here.
 */
internal suspend fun projectVoiceCallState(
    messages: List<UIMessage>,
    assistantId: String,
    readRecord: suspend (String) -> OrbisVoiceCallRecord?,
): List<UIMessage> {
    val index = messages.indexOfLast { it.orbisVoiceCallId != null }
    if (index < 0) return messages
    val lastCall = messages[index]
    if (lastCall.orbisVoiceCallKind in setOf("archive", "summary", "ended_notice")) return messages
    val callId = checkNotNull(lastCall.orbisVoiceCallId)
    val record = readRecord(callId) ?: return messages
    if (record.id != callId || record.assistantId != assistantId ||
        record.status !in setOf(OrbisVoiceCallStatus.ENDED, OrbisVoiceCallStatus.INTERRUPTED)) return messages

    // Deliberately omit END/CALL_MODE control syntax and a fresh timestamp/duration. This is the
    // state of THIS historical call only, never a new user request or the state of another call.
    val state = UIMessagePart.Text(
        text = "[宿主历史状态：本条消息所属通话 $callId 已结束，收音与播放已停止。" +
            "本条中的通话模式及朗读说明仅描述当时状态；迟到的转写仍是原文，不会恢复通话。" +
            "此注记不是新的用户发言，也不是再次挂断事件。]",
        metadata = buildJsonObject {
            put("orbis_voice_call_projection", "ended_v1")
            put("call_id", callId)
        },
    )
    // Exact part equality, not matching user prose or a bare metadata key, makes a second pass
    // inert while preserving literal lookalikes and every original tool/attachment part.
    if (lastCall.parts.any { it == state }) return messages
    return messages.mapIndexed { current, message ->
        if (current == index) message.copy(parts = message.parts + state) else message
    }
}
