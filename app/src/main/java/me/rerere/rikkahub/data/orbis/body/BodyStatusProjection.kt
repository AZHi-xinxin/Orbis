package me.rerere.rikkahub.data.orbis.body

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val BODY_PROJECTION = "orbis_body_status_projection"

private fun UIMessagePart.isBodyStatus(): Boolean = this is UIMessagePart.Text && metadata?.let {
    (it[BODY_PROJECTION] as? JsonPrimitive)?.contentOrNull == "v1" &&
        (it["source"] as? JsonPrimitive)?.contentOrNull == "orbis_body_status" &&
        (it["request_only"] as? JsonPrimitive)?.booleanOrNull == true &&
        (it["human_authored"] as? JsonPrimitive)?.booleanOrNull == false &&
        (it["instruction_authority"] as? JsonPrimitive)?.contentOrNull == "none"
} == true

/** Request copies only. Never create another human turn, rewrite history, or split a tool unit. */
internal fun projectBodyStatus(messages: List<UIMessage>, report: String?): List<UIMessage> {
    fun clean(parts: List<UIMessagePart>): List<UIMessagePart> = parts.filterNot { it.isBodyStatus() }.map {
        if (it is UIMessagePart.Tool) it.copy(output = clean(it.output)) else it
    }
    val result = messages.map { it.copy(parts = clean(it.parts)) }
    if (report.isNullOrBlank()) return result
    val part = UIMessagePart.Text(report, buildJsonObject {
        put(BODY_PROJECTION, "v1"); put("source", "orbis_body_status")
        put("request_only", true); put("human_authored", false); put("instruction_authority", "none")
    })
    val user = result.indexOfLast { it.role == MessageRole.USER }
    if (user >= 0) return result.mapIndexed { i, message ->
        if (i == user) message.copy(parts = message.parts + part) else message
    }
    // Compaction can remove every USER; retain this wake's frozen status in a completed result.
    for (i in result.indices.reversed()) {
        val message = result[i]
        if (message.role != MessageRole.ASSISTANT) continue
        val tool = message.parts.indexOfLast { it is UIMessagePart.Tool && it.isExecuted }
        if (tool < 0) continue
        return result.mapIndexed { index, original ->
            if (index != i) original else original.copy(parts = original.parts.mapIndexed { j, p ->
                if (j == tool) (p as UIMessagePart.Tool).copy(output = p.output + part) else p
            })
        }
    }
    return result
}
