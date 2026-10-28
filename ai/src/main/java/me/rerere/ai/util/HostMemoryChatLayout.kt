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
        val (memory, authored) = message.parts.partition { it.isRequestHostData() }
        if (memory.isEmpty()) add(message) else {
            add(UIMessage.system("").copy(isSynthetic = true, parts = memory))
            add(message.copy(parts = authored))
        }
    }
}

/** A standard wire name lets a compatible gateway identify data without parsing user text. */
internal fun UIMessage.isRequestHostDataMessage(): Boolean = role == MessageRole.SYSTEM &&
    isSynthetic && parts.isNotEmpty() && parts.all { it.isRequestHostData() }

/** Official Pro only: changing tail SYSTEM notices invalidate its history cache. Move explicit
 * request-only notices onto the current human carrier in the upstream copy. The generation
 * snapshot (including its append-only tool lineage) and stored chat remain untouched. */
internal fun proHostNoticeDataLayout(messages: List<UIMessage>): List<UIMessage> {
    val human = messages.indexOfLast { it.role == MessageRole.USER }
    if (human < 0) return messages // Compaction may leave no human turn; never fabricate one.
    val notices = messages.withIndex().filter { (i, message) ->
        i > human && message.isRequestHostDataMessage()
    }
    if (notices.isEmpty()) return messages
    val indices = notices.map { it.index }.toSet()
    val parts = notices.flatMap { it.value.parts }
    return messages.mapIndexedNotNull { i, message -> when {
        i in indices -> null
        i == human -> message.copy(parts = message.parts + parts)
        else -> message
    } }
}

private fun UIMessagePart.isRequestHostData(): Boolean =
            this is UIMessagePart.Text && metadata?.let { tag ->
                (((tag["orbis_local_memory_projection"] as? JsonPrimitive)?.booleanOrNull == true &&
                    (tag["source"] as? JsonPrimitive)?.contentOrNull == "orbis_local_memory") ||
                    ((tag["orbis_body_status_projection"] as? JsonPrimitive)?.contentOrNull == "v1" &&
                    (tag["source"] as? JsonPrimitive)?.contentOrNull == "orbis_body_status") ||
                    ((tag["orbis_compaction_reminder"] as? JsonPrimitive)?.booleanOrNull == true &&
                    (tag["source"] as? JsonPrimitive)?.contentOrNull == "orbis_host")) &&
                    (tag["human_authored"] as? JsonPrimitive)?.booleanOrNull == false &&
                    (tag["instruction_authority"] as? JsonPrimitive)?.contentOrNull == "none" &&
                    (tag["request_only"] as? JsonPrimitive)?.booleanOrNull == true
            } == true
