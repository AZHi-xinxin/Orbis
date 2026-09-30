package me.rerere.ai.ui

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole

/** Recognize only in-memory host data, never ordinary user/assistant text or imported history. */
internal fun UIMessage.isHostContextNotice(): Boolean =
    role == MessageRole.SYSTEM && isSynthetic && parts.isNotEmpty() && parts.all { part ->
        part is UIMessagePart.Text &&
            part.metadata?.get("orbis_compaction_reminder") == JsonPrimitive(true) &&
            part.metadata?.get("source") == JsonPrimitive("orbis_host") &&
            part.metadata?.get("human_authored") == JsonPrimitive(false) &&
            part.metadata?.get("instruction_authority") == JsonPrimitive("none")
    }

/**
 * APIs with one system field used to take only the first SYSTEM message. Keep that exact
 * behavior without a host notice. With a notice, retain the original main system parts and
 * append only verified host notices; never promote human/tool/model content into that field.
 *
 * Unlike Chat Completions' ordered SYSTEM messages, these APIs move tail notices into their
 * single system field. At the reminder threshold this may invalidate downstream prompt cache;
 * keeping the main prompt and host provenance is more important than pretending otherwise.
 */
internal fun List<UIMessage>.singleSystemMessageWithHostNotices(): UIMessage? {
    val notices = filter { it.isHostContextNotice() }
    if (notices.isEmpty()) return firstOrNull { it.role == MessageRole.SYSTEM }

    val main = firstOrNull { it.role == MessageRole.SYSTEM && !it.isHostContextNotice() }
    return (main ?: notices.first()).copy(
        parts = main?.parts.orEmpty() + notices.flatMap { it.parts },
    )
}
