package me.rerere.rikkahub.ui.pages.chat

import kotlin.uuid.Uuid

internal data class ForegroundChatNavigation(val conversationId: String, val appendRoute: Boolean)

/** Normalize the externally supplied ID before RouteActivity creates a ChatPage. */
internal fun foregroundChatNavigation(rawId: String?, currentId: String?): ForegroundChatNavigation? {
    val target = rawId?.let { runCatching { Uuid.parse(it).toString() }.getOrNull() } ?: return null
    val current = currentId?.let { runCatching { Uuid.parse(it).toString() }.getOrNull() }
    return ForegroundChatNavigation(target, current != target)
}
