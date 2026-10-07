package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.hostToolFailure
import kotlin.uuid.Uuid

/** Scheduling only: durable acceptance, deduplication and terminal receipts live in OrbisEventInbox.
 * Never exposed through the human editable queue, and never resumes a failed old generation.
 */
class AutomaticWakeQueue {
    private val queue = MessageQueue()
    val pending: List<QueuedMessage> get() = queue.state.value.messages

    fun enqueue(parts: List<UIMessagePart>, answer: Boolean, id: Uuid, eventId: String) {
        require(eventId == id.toString()) { "automatic_wake_id_mismatch" }
        queue.enqueue(parts, answer, id = id, orbisEventId = eventId)
    }

    fun remove(id: Uuid): QueuedMessage? = queue.remove(id)
    internal fun holdAllForRecovery(eventIds: Set<String>? = null): Int =
        if (eventIds == null) queue.holdAllInputsForFreshRecovery()
        else queue.holdInputsForRecovery(pending.filter { it.orbisEventId in eventIds }.map { it.id }.toSet())
    internal fun takeNext(): QueuedMessage? = queue.takeNext()
}

/** One conversation, one generation. A human queue pause is not the sentinel master switch.
 * A hard safety hold blocks automatic work only; explicit human recovery remains possible.
 */
internal fun takeNextConversationInput(
    human: MessageQueue,
    automatic: AutomaticWakeQueue,
    busy: Boolean,
    pendingApproval: Boolean,
    automaticAllowed: Boolean,
): QueuedMessage? {
    if (busy || pendingApproval) return null
    return human.takeNext() ?: if (automaticAllowed) automatic.takeNext() else null
}

/** A stop before the edit-lock/FGS gate is still a stop, not permission to replay on restart. */
internal fun interruptedWakeBeforeBody(bodyEntered: Boolean, failed: Boolean, state: String?): Boolean =
    !bodyEntered && failed && state in setOf("accepted", "queued")

/** Must run before committing/clearing recovery with unknown external tool results. */
internal fun requireAutomaticRecoveryHold(persistHold: () -> Boolean) {
    check(persistHold()) { "automatic_recovery_hold_not_durable" }
}

/** Dev35 could commit interrupted tool receipts and clear its journal with only a human pause.
 * Migrate that evidence before the independent automatic lane can run. Only host metadata counts;
 * automatic/voice USER messages are not the explicit ordinary human input that acknowledges it.
 */
internal fun needsLegacyAutomaticToolHold(humanPaused: Boolean, messages: List<UIMessage>): Boolean {
    if (!humanPaused) return false
    for (message in messages.asReversed()) {
        if (message.role == MessageRole.USER && message.orbisEvent == null &&
            message.orbisVoiceCallId == null && message.orbisVoiceCallKind == null && !message.isSynthetic) return false
        if (message.parts.filterIsInstance<UIMessagePart.Tool>().any { tool ->
                val failure = tool.hostToolFailure()
                failure != null && failure.executionPerformed == null
            }) return true
    }
    return false
}

/** The completion marker is separate from the active hold. Explicitly resuming a migrated hold
 * must stay acknowledged after another provider failure/restart, even without a new human message.
 */
internal fun migrateLegacyAutomaticToolHoldOnce(
    migrationDone: Boolean,
    humanPaused: Boolean,
    messages: List<UIMessage>,
    persistHold: () -> Boolean,
    persistMigrationDone: () -> Unit,
) {
    if (migrationDone) return
    if (needsLegacyAutomaticToolHold(humanPaused, messages)) requireAutomaticRecoveryHold(persistHold)
    persistMigrationDone()
}

/** A failed terminal receipt must never look like permission to deliver again. The hold is
 * independent of the editable queue; if even that cannot be saved, block this process as well.
 */
internal fun persistAutomaticReceiptOrHold(
    persistReceipt: () -> Unit,
    persistHold: () -> Boolean,
    blockOnHoldFailure: () -> Unit,
): Boolean = try {
    persistReceipt()
    true
} catch (_: Exception) {
    val held = try { persistHold() } catch (_: Exception) { false }
    if (!held) blockOnHoldFailure()
    false
}
