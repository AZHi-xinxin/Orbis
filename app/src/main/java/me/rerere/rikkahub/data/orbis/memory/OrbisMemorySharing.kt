package me.rerere.rikkahub.data.orbis.memory

import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/** Both libraries opt in. Linking never moves, copies or deletes stored notes. */
internal fun sharedMemoryOwners(currentId: Uuid, assistants: List<Assistant>): Map<String, String> {
    val current = assistants.firstOrNull { it.id == currentId } ?: return emptyMap()
    return (listOf(current) + if (current.orbisSharedMemory)
        assistants.filter { it.id != currentId && it.orbisSharedMemory } else emptyList())
        .associate { it.id.toString() to it.name }
}

/** Shared excerpts carry their source; a foreign pin never replaces this assistant's pins. */
internal fun sharedMemorySelection(
    mode: OrbisMemoryMode,
    own: OrbisMemorySelection,
    shared: List<OrbisMemorySelected>,
): OrbisMemorySelection = limitFrozenMemory(OrbisMemorySelection(own.entries + shared), mode)

internal fun sharedMemoryEntry(owner: String, name: String, entry: OrbisMemorySelected): OrbisMemorySelected =
    entry.copy(id = "$owner/${entry.id}", kind = if (entry.kind == "pinned") "summary" else entry.kind,
        sourceAssistantId = owner, sourceAssistantName = name, sourceNoteId = entry.id)
