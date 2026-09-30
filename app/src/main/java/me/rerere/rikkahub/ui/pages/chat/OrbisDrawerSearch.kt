package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

data class OrbisDrawerSearchResult(
    val conversation: Conversation,
    val snippet: String = "标题匹配",
    val nodeId: Uuid? = null,
)

/** Keep one row per same-assistant conversation, preferring a real FTS message anchor. */
fun mergeOrbisDrawerSearch(
    assistantId: Uuid,
    titleMatches: List<OrbisDrawerSearchResult>,
    messageMatches: List<OrbisDrawerSearchResult>,
): List<OrbisDrawerSearchResult> = (messageMatches + titleMatches)
    .filter { it.conversation.assistantId == assistantId }
    .distinctBy { it.conversation.id }
    .take(50)
