package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.GoogleChatCompletionsThoughtMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata

/** Only preserve the documented opaque signature, not arbitrary provider response fields. */
internal fun JsonObject.googleChatCompletionsSignatureMetadata(): JsonObject? {
    val extra = this["extra_content"] as? JsonObject ?: return null
    val google = extra["google"] as? JsonObject ?: return null
    val signature = google["thought_signature"] as? JsonPrimitive ?: return null
    if (!signature.isString || signature.content.isEmpty()) return null
    return GoogleChatCompletionsThoughtMetadata(signature.content).toMetadata()
}

/** Outgoing copy only. Retain other metadata and the stored signature for a later Gemini turn. */
internal fun UIMessage.withoutGoogleChatCompletionsToolSignatures(): UIMessage = copy(parts = parts.map { part ->
    val metadata = part.metadata
    if (part is UIMessagePart.Tool && metadata?.containsKey("google_chat_completions_thought_signature") == true) {
        part.copy(metadata = JsonObject(metadata - "google_chat_completions_thought_signature").takeIf { it.isNotEmpty() })
    } else part
})
