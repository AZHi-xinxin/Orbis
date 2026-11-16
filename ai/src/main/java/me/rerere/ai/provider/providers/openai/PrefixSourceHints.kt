package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Match exact serialized text contents, not an assumed index: tool serialization can expand
 * one UI message into several wire messages. Never export content, IDs, names or content hashes.
 * A custom body that replaces content won't match and is honestly reported as unknown. */
internal fun prefixSystemSourceHints(messages: List<UIMessage>): Map<String,List<String>> {
    val known=mapOf("orbis_local_memory" to "local_memory", "orbis_body_status" to "body_status",
        "orbis_community_hint" to "community", "orbis_model_continuity" to "model_continuity",
        "orbis_user_message_time" to "message_time", "orbis_observation" to "observation",
        "orbis_host" to "host_notice")
    val allowed=setOf("system_prompt","worldbook","mode_injection")
    val result=mutableMapOf<String,List<String>>()
    for(message in messages) {
        if(message.role!=MessageRole.SYSTEM || message.parts.isEmpty() || message.parts.any { it !is UIMessagePart.Text }) continue
        val parts=message.parts.filterIsInstance<UIMessagePart.Text>()
        val content=if(parts.size==1) JsonPrimitive(parts.single().text) else buildJsonArray {
            parts.forEach { add(buildJsonObject { put("type","text");put("text",it.text) }) }
        }
        val sources=parts.flatMap { part ->
            val source=(part.metadata?.get("source") as? JsonPrimitive)?.contentOrNull
            if(source=="orbis_prompt_injection") {
                (part.metadata?.get("diagnostic_sources") as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it in allowed }
            } else listOf(known[source] ?: "system_prompt_or_history")
        }
        result[content.toString()]=(result[content.toString()].orEmpty()+sources).distinct().sorted()
    }
    return result
}
