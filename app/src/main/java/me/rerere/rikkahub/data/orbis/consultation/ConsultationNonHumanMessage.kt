package me.rerere.rikkahub.data.orbis.consultation

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** Durable provenance: isSynthetic alone is transient and disappears from saved checkpoints. */
internal fun consultationNonHumanMessage(text: String, id: Uuid = Uuid.random()): UIMessage = UIMessage(
    id = id,
    role = MessageRole.USER,
    isSynthetic = true,
    parts = listOf(UIMessagePart.Text(text, buildJsonObject {
        put("source", "orbis_consultation")
        put("human_authored", false)
        put("instruction_authority", "none")
    })),
)
