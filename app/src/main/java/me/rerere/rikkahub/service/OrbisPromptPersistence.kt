package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.OrbisConversationPrompt

/** Caller holds the session prompt-edit mutex. Failed drafts are never published to readers. */
internal suspend fun persistOrbisPromptEdit(
    state: MutableStateFlow<Conversation>,
    prompt: OrbisConversationPrompt,
    persist: suspend (Conversation, OrbisConversationPrompt) -> Unit,
) {
    persist(state.value, prompt)
    state.update { it.copy(orbisPrompt = prompt) }
}

/** Whole-conversation snapshots may predate a prompt edit; the committed field is authoritative. */
internal fun withCommittedOrbisPrompt(snapshot: Conversation, current: Conversation): Conversation =
    snapshot.copy(orbisPrompt = current.orbisPrompt)
