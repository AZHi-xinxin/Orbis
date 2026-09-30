package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

/**
 * A generation's already-transformed, durably saved history is frozen. Output transforms see
 * only the active suffix, so a non-idempotent visual transform cannot rewrite historical text
 * on every token or diverge from the checkpoint's protected prefix. Rebuild after compact ACK.
 *
 * This does not authorize a database write or tool call. The caller still owns durable baseline,
 * owner/epoch/presentation checks and the journal acknowledgement before publishing results.
 */
internal class OutputGenerationScope(frozenPrefix: List<UIMessage>) {
    // Copy the list container, not the immutable messages: retain structural sharing while
    // preventing a caller from changing scope boundaries by mutating its temporary list.
    private val frozenMessages = frozenPrefix.toList()
    val frozenPrefixCount: Int get() = frozenMessages.size

    suspend fun apply(
        messages: List<UIMessage>,
        transform: suspend (List<UIMessage>) -> List<UIMessage>,
    ): List<UIMessage> {
        check(messages.size >= frozenPrefixCount) { "Generation output prefix is missing." }
        for (index in frozenMessages.indices) {
            check(messages[index].id == frozenMessages[index].id) {
                "Generation output prefix identity changed."
            }
        }
        if (messages.size == frozenPrefixCount) return frozenMessages.toList()

        val active = messages.subList(frozenPrefixCount, messages.size).toList()
        val activeIds = active.map { it.id }
        val transformed = transform(active)
        check(transformed.size == activeIds.size) { "Generation output transform changed message count." }
        for (index in activeIds.indices) {
            check(transformed[index].id == activeIds[index]) {
                "Generation output transform changed message identity or order."
            }
        }
        return frozenMessages + transformed
    }
}

/** A trailing assistant may receive approved-tool results or further model output this wake. */
internal fun frozenOutputPrefixCount(messages: List<UIMessage>): Int =
    if (messages.lastOrNull()?.role == MessageRole.ASSISTANT) messages.lastIndex else messages.size
