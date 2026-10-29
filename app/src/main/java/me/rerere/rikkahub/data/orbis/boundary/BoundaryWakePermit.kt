package me.rerere.rikkahub.data.orbis.boundary

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Bound to one already-claimed notification job, never persisted or exposed as a tool argument. */
class BoundaryWakePermit private constructor(
    private val owner: String, private val conversation: String, private val pauses: Set<Long>,
) : AbstractCoroutineContextElement(Key) {
    fun permits(state: AssistantBoundary, owner: String, conversation: String?, now: Long): Boolean =
        this.owner == owner && this.conversation == conversation &&
            state.activePauseRevisions(conversation, now).all { it in pauses }

    companion object Key : CoroutineContext.Key<BoundaryWakePermit> {
        fun capture(state: AssistantBoundary, owner: String, conversation: String, now: Long) =
            BoundaryWakePermit(owner, conversation, state.activePauseRevisions(conversation, now))
    }
}

private fun AssistantBoundary.activePauseRevisions(conversation: String?, now: Long): Set<Long> =
    if ("conversation" !in grants) emptySet() else
        (listOfNotNull(assistantPause, conversations[conversation])).filter {
            it.paused && (it.until == null || it.until > now)
        }.map { it.revision }.toSet()
