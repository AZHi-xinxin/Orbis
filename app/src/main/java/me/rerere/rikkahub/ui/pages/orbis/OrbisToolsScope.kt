package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/** A chat-origin entry carries both identifiers, independently of the global selection. */
internal fun orbisToolsRoute(assistantId: String?, conversationId: String?): Screen =
    if (assistantId == null && conversationId == null) Screen.OrbisTools
    else Screen.OrbisConversationTools(assistantId.orEmpty(), conversationId.orEmpty())

internal data class OrbisToolsScope(val assistant: Assistant, val conversationId: String?) {
    fun imageWorkshopRoute() = Screen.ImageWorkshop(assistant.id.toString(), conversationId)
}

/** Malformed or removed explicit targets never become a general/global entry. */
internal fun orbisToolsScope(
    settings: Settings,
    assistantId: String? = null,
    conversationId: String? = null,
): OrbisToolsScope? {
    if (assistantId != null || conversationId != null) {
        if (assistantId == null || conversationId == null ||
            runCatching { Uuid.parse(conversationId) }.isFailure) return null
    }
    val assistant = orbisSettingsAssistant(settings, assistantId) ?: return null
    return OrbisToolsScope(assistant, conversationId)
}

/** The workshop pins its opening owner even for legacy general entries. */
internal fun orbisWorkshopAssistant(settings: Settings, pinnedOwnerId: String, conversationId: String?): Assistant? {
    if (conversationId != null && runCatching { Uuid.parse(conversationId) }.isFailure) return null
    return orbisSettingsAssistant(settings, pinnedOwnerId)
}
