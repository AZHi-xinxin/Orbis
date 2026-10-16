package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/** Null means a general settings entry. An explicit but unavailable owner never falls back. */
internal fun orbisSettingsAssistant(settings: Settings, ownerId: String? = null): Assistant? {
    if (settings.init) return null
    val target = if (ownerId == null) settings.assistantId
        else runCatching { Uuid.parse(ownerId) }.getOrNull() ?: return null
    return settings.assistants.singleOrNull { it.id == target }
}

/** Apply only the requested owner's MCP selection to the latest store snapshot. */
internal fun orbisSettingsWithMcpSelection(
    current: Settings,
    ownerId: String,
    proposed: Assistant,
): Settings {
    val owner = orbisSettingsAssistant(current, ownerId) ?: return current
    if (proposed.id != owner.id) return current
    return current.copy(assistants = current.assistants.map { assistant ->
        if (assistant.id == owner.id) assistant.copy(mcpServers = proposed.mcpServers) else assistant
    })
}
