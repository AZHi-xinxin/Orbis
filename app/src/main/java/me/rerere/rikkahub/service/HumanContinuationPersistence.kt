package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.withCommittedMessageRecalls

/** A failed Room write must not become the next lane's supposedly durable baseline. */
internal suspend fun commitConversationBeforePublish(commit: suspend () -> Unit, publish: () -> Unit) {
    commit()
    publish()
}

/** The displayed baseline can include a streamed tail which has not reached Room yet.
 * A journal proof may bridge that exact gap; the actual write still compares the freshly
 * read Room value in its transaction, so a concurrent edit/deletion cannot be overwritten.
 */
internal suspend fun commitHumanContinuationSnapshot(
    expected: Conversation,
    snapshot: Conversation,
    read: suspend () -> Conversation?,
    proveInFlightBaseline: (Conversation, Conversation) -> Boolean,
    commit: suspend (Conversation, Conversation) -> Conversation?,
): Conversation? = commitHumanContinuationSnapshot(expected, snapshot, read, proveInFlightBaseline, commit, null)

internal suspend fun commitHumanContinuationSnapshot(
    expected: Conversation,
    snapshot: Conversation,
    read: suspend () -> Conversation?,
    proveInFlightBaseline: (Conversation, Conversation) -> Boolean,
    commit: suspend (Conversation, Conversation) -> Conversation?,
    beforeCommit: (suspend (Conversation, Conversation) -> Unit)?,
): Conversation? {
    val stored = read() ?: return null
    // A transaction may have succeeded before its caller timed out receiving the result.
    if (sameDurableGenerationSnapshot(snapshot, stored)) return stored
    if (!sameDurableGenerationSnapshot(expected, stored) && !proveInFlightBaseline(stored, expected)) return null
    val candidate = withCommittedMessageRecalls(snapshot, stored)
    beforeCommit?.invoke(stored, candidate)
    return commit(stored, candidate)?.takeIf { sameDurableGenerationSnapshot(candidate, it) }
}
