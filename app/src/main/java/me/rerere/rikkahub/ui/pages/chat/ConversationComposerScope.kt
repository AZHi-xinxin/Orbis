package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.SearchMode
import kotlin.uuid.Uuid

internal data class ConversationComposerScope(val assistant: Assistant, val model: Model?)

/** Match generation's owner/model resolution, never the app-wide selected assistant. */
internal fun conversationComposerScope(settings: Settings, ownerId: Uuid): ConversationComposerScope? {
    if (settings.init) return null
    val owner = settings.assistants.singleOrNull { it.id == ownerId } ?: return null
    return ConversationComposerScope(owner, settings.findModelById(owner.chatModelId ?: settings.chatModelId))
}

internal fun conversationComposerWithModel(current: Settings, ownerId: Uuid, modelId: Uuid): Settings {
    val scope = conversationComposerScope(current, ownerId) ?: return current
    val selected = current.findModelById(modelId)?.takeIf { it.type == ModelType.CHAT } ?: return current
    return current.copy(assistants = current.assistants.map {
        if (it.id == scope.assistant.id) it.copy(chatModelId = selected.id) else it
    })
}

internal fun conversationComposerWithReasoning(
    current: Settings, ownerId: Uuid, expectedModelId: Uuid?, level: ReasoningLevel,
): Settings {
    val scope = conversationComposerScope(current, ownerId) ?: return current
    if (scope.model?.id != expectedModelId) return current
    return current.copy(assistants = current.assistants.map {
        if (it.id == scope.assistant.id) it.copy(reasoningLevel = level) else it
    })
}

internal fun conversationComposerWithSearch(
    current: Settings, ownerId: Uuid, expectedModelId: Uuid?, mode: SearchMode,
): Settings {
    val scope = conversationComposerScope(current, ownerId) ?: return current
    val model = scope.model
    if (model?.id != expectedModelId || (model == null && mode == SearchMode.BUILT_IN)) return current
    return current.copy(
        assistants = current.assistants.map {
            if (it.id == scope.assistant.id) it.copy(enableWebSearch = mode == SearchMode.LOCAL) else it
        },
        // Built-in tools remain model-scoped, as in generation; only edit this owner's model.
        providers = if (model == null) current.providers else current.providers.map { provider ->
            provider.editModel(model.copy(tools = if (mode == SearchMode.BUILT_IN) {
                model.tools + BuiltInTools.Search
            } else model.tools - BuiltInTools.Search))
        },
    )
}
