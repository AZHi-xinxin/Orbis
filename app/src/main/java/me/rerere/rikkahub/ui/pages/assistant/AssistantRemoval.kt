package me.rerere.rikkahub.ui.pages.assistant

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/** No associated data may be deleted before the current settings confirm the removal. */
internal suspend fun removeAssistantAfterConfirmedSave(
    id: Uuid,
    update: suspend ((Settings) -> Settings) -> Boolean,
    current: () -> Settings,
    cleanup: suspend (Assistant) -> Unit,
): Boolean {
    var removed: Assistant? = null
    val saved = update { latest ->
        removed = latest.assistants.firstOrNull { it.id == id }
        latest.copy(assistants = latest.assistants.filterNot { it.id == id })
    }
    if (!saved) return false
    val confirmed = current()
    // Normalization can re-add a built-in assistant. A write is not proof of removal.
    if (confirmed.init || confirmed.assistants.any { it.id == id }) return false
    val target = removed ?: return false
    cleanup(target)
    return true
}
