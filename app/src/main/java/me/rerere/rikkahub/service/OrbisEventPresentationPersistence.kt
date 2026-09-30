package me.rerere.rikkahub.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.OrbisEventPresentationEdit
import me.rerere.rikkahub.data.model.applyEventPresentation
import kotlin.uuid.Uuid

/** Caller holds the session persistence mutex. No draft is visible before storage succeeds. */
internal suspend fun persistOrbisEventPresentation(
    state: MutableStateFlow<Conversation>,
    owner: Uuid,
    edit: OrbisEventPresentationEdit,
    persist: suspend () -> Unit,
) {
    // Validate the live target, not a UI/paging snapshot. Do not save this validation copy.
    state.value.applyEventPresentation(owner, edit)
    // Once storage starts, publish its outcome even if the card/window is disposed meanwhile.
    withContext(NonCancellable) {
        persist()
        state.update { current -> current.applyEventPresentation(owner, edit) }
    }
}
