package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

internal val companionMemoryToolNames = setOf("read_memory", "save_memory")

internal fun selectedCompanionToolNames(options: List<LocalToolOption>, catalogNames: List<String>): Set<String> =
    catalogNames.filterTo(linkedSetOf()) { name ->
        if (name in companionMemoryToolNames) LocalToolOption.CompanionMemory in options
        else LocalToolOption.CompanionDevice in options
    }

/** Update only the explicitly displayed identity; do not overwrite concurrent unrelated settings. */
internal fun Settings.withNativeToolSelection(assistantId: Uuid, option: LocalToolOption, enabled: Boolean): Settings {
    require(!init && assistants.any { it.id == assistantId }) { "assistant_missing" }
    return copy(assistants = assistants.map { assistant ->
        if (assistant.id != assistantId) assistant else assistant.copy(localTools =
            if (enabled) (assistant.localTools + option).distinct() else assistant.localTools - option)
    })
}
