package me.rerere.ai.ui

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid

/** An explicitly selected visible body, not a live link or a new instruction role. */
@Serializable
data class OrbisMessageQuote(
    val sourceConversationId: Uuid,
    val sourceNodeId: Uuid,
    val sourceMessageId: Uuid,
    val sourceRole: MessageRole,
    val bodySnapshot: String,
    val sourceCreatedAt: String,
    val version: Int = 1,
) {
    fun isValid(): Boolean = version == 1 &&
        sourceRole in setOf(MessageRole.USER, MessageRole.ASSISTANT) &&
        bodySnapshot.isNotBlank() && bodySnapshot.length <= MAX_BODY_CHARS &&
        sourceCreatedAt.length <= 100

    override fun toString(): String = "OrbisMessageQuote(version=$version, body=[redacted])"

    companion object { const val MAX_BODY_CHARS = 100_000 }
}
