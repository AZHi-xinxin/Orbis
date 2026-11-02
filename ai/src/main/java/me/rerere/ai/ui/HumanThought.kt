package me.rerere.ai.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole

/** A human-authored text part, not provider reasoning. Empty is meaningful. */
const val HUMAN_THOUGHT_MARKER = "orbis_human_thought"

fun UIMessagePart.isHumanThought(): Boolean = this is UIMessagePart.Text &&
    metadata?.get(HUMAN_THOUGHT_MARKER) == JsonPrimitive(true)

fun humanThoughtPart(text: String) = UIMessagePart.Text(
    text = text,
    metadata = JsonObject(mapOf(HUMAN_THOUGHT_MARKER to JsonPrimitive(true))),
)

/** Stable wire projection: one USER message, one send; never mutates stored parts. */
fun UIMessage.projectHumanThought(): UIMessage {
    if (role != MessageRole.USER || parts.none { it.isHumanThought() }) return this
    val thoughts = parts.filter { it.isHumanThought() }.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }.ifBlank { "（为空）" }
    val body = parts.filterNot { it.isHumanThought() }
    return copy(parts = listOf(UIMessagePart.Text("【人类思考链】\n$thoughts\n\n【正文回复】")) + body)
}

fun List<UIMessage>.projectHumanThoughts(): List<UIMessage> = map { it.projectHumanThought() }
