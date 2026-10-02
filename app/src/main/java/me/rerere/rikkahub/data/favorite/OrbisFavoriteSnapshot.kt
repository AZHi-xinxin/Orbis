package me.rerere.rikkahub.data.favorite

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisMessageQuote
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import kotlin.uuid.Uuid

@Serializable
enum class OrbisFavoritePartKind { BODY, REASONING }

@Serializable
data class OrbisFavoriteTextPart(val kind: OrbisFavoritePartKind, val text: String)

/** Deliberately excludes opaque tool arguments, provider signatures and attachment bytes/URLs. */
@Serializable
data class OrbisFavoriteSnapshot(
    val conversationId: Uuid,
    val nodeId: Uuid,
    val messageId: Uuid,
    val role: MessageRole,
    val messageCreatedAt: String,
    val parts: List<OrbisFavoriteTextPart>,
    val unarchivedPartCount: Int = 0,
    val quote: OrbisMessageQuote? = null,
    val version: Int = 1,
) {
    fun isValid() = version == 1 && role in setOf(MessageRole.USER, MessageRole.ASSISTANT) &&
        unarchivedPartCount >= 0 && (quote == null || quote.isValid())

    override fun toString() = "OrbisFavoriteSnapshot(version=$version, parts=[redacted])"
}

fun NodeFavoriteTarget.favoriteSnapshot(): OrbisFavoriteSnapshot {
    check(node.id == nodeId) { "收藏目标已改变。" }
    val message = node.currentMessage
    check(message.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) && message.orbisEvent == null) {
        "只能收藏自己或 AI 的消息。"
    }
    return OrbisFavoriteSnapshot(
        conversationId, nodeId, message.id, message.role, message.createdAt.toString(),
        parts = message.parts.mapNotNull {
            when (it) {
                is UIMessagePart.Text -> OrbisFavoriteTextPart(OrbisFavoritePartKind.BODY, it.text)
                is UIMessagePart.Reasoning -> OrbisFavoriteTextPart(OrbisFavoritePartKind.REASONING, it.reasoning)
                else -> null
            }
        },
        unarchivedPartCount = message.parts.count { it !is UIMessagePart.Text && it !is UIMessagePart.Reasoning },
        quote = message.orbisQuote,
    )
}
