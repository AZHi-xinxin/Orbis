package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/** Optional, conversation-scoped instructions. Turning them off retains the author's draft. */
@Serializable
data class OrbisConversationPrompt(
    val text: String = "",
    val enabled: Boolean = false,
    val worldBookText: String = "",
    val worldBookEnabled: Boolean = false,
) {
    fun effectiveText(): String? = text.takeIf { enabled && it.isNotBlank() }
    fun effectiveWorldBook(): String? = worldBookText.takeIf { worldBookEnabled && it.isNotBlank() }
    fun isActive(): Boolean = effectiveText() != null || effectiveWorldBook() != null
}

/** Append after the existing system context so the assistant, memory and tools remain intact. */
internal fun appendOrbisConversationPrompt(
    systemPrompt: String,
    prompt: OrbisConversationPrompt,
): String {
    val text = prompt.effectiveText()
    val worldBook = prompt.effectiveWorldBook()
    if (text == null && worldBook == null) return systemPrompt
    return buildString {
        append(systemPrompt)
        if (worldBook != null) {
            if (isNotEmpty()) append("\n\n")
            append("当前会话的世界书（背景资料与设定）：\n")
            append(worldBook)
        }
        if (text != null) {
            if (isNotEmpty()) append("\n\n")
            append("当前会话的补充提示：\n")
            append(text)
        }
    }
}
