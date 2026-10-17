package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.privateroom.privateRoomAutomaticSpeechText
import kotlin.uuid.Uuid

/** A completed reply, not a signal to read whichever Compose snapshot happens to be visible. */
data class ChatGenerationDone(val conversationId: Uuid, val messageId: Uuid, val text: String)

internal fun completedSpeechReply(conversation: Conversation, completed: Boolean): ChatGenerationDone? {
    if (!completed || conversation.isConsultation) return null
    val messages = conversation.currentMessages
    val reply = messages.lastOrNull() ?: return null
    if (reply.role != MessageRole.ASSISTANT || reply.orbisVoiceCallId != null) return null
    val turn = messages.drop((messages.indexOfLast { it.role == MessageRole.USER }).coerceAtLeast(0))
    if (turn.any { it.getTools().any { tool -> !tool.isExecuted } }) return null
    val text = reply.privateRoomAutomaticSpeechText() ?: return null
    return ChatGenerationDone(conversation.id, reply.id, text)
}

internal fun ChatGenerationDone.matches(conversation: Conversation): Boolean =
    conversation.id == conversationId && completedSpeechReply(conversation, completed = true) == this

/** No replay when enabling speech or leaving a call; duplicate completion callbacks speak once. */
internal class AutomaticSpeechDelivery {
    private var lastSeen: Pair<Uuid, Uuid>? = null

    fun claim(event: ChatGenerationDone, enabled: Boolean, current: Boolean,
        voiceActive: Boolean, screenOwnsSpeech: Boolean): Boolean {
        val key = event.conversationId to event.messageId
        if (key == lastSeen) return false
        lastSeen = key
        return enabled && current && !voiceActive && !screenOwnsSpeech
    }
}
