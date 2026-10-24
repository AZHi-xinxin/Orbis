package me.rerere.ai.util

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Chat Completions gateways derive recall/capture from user messages. Keep host retrieval
 * separate on that wire, immediately before its human carrier instead of in the stable prefix.
 * Other protocols keep their supported content-block layout. This is request-only: never parse
 * text delimiters, invent user text, touch saved history or split a tool call/result unit.
 */
internal fun chatCompletionsHostMemoryLayout(messages: List<UIMessage>): List<UIMessage> = buildList {
    for (message in messages) {
        if (message.role != MessageRole.USER) { add(message); continue }
        val (memory, authored) = message.parts.partition { part ->
            part is UIMessagePart.Text && part.metadata?.let { tag ->
                (((tag["orbis_local_memory_projection"] as? JsonPrimitive)?.booleanOrNull == true &&
                    (tag["source"] as? JsonPrimitive)?.contentOrNull == "orbis_local_memory") ||
                    ((tag["orbis_body_status_projection"] as? JsonPrimitive)?.contentOrNull == "v1" &&
                    (tag["source"] as? JsonPrimitive)?.contentOrNull == "orbis_body_status")) &&
                    (tag["human_authored"] as? JsonPrimitive)?.booleanOrNull == false &&
                    (tag["instruction_authority"] as? JsonPrimitive)?.contentOrNull == "none" &&
                    (tag["request_only"] as? JsonPrimitive)?.booleanOrNull == true
            } == true
        }
        if (memory.isEmpty()) add(message) else {
            add(UIMessage.system("").copy(isSynthetic = true, parts = memory))
            add(message.copy(parts = authored))
        }
    }
}
