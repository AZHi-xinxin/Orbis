package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** Immutable proof of one accepted human input, captured before its first Room write.
 * This is not a generation receipt: adopting it never dispatches its model or tools.
 */
internal class HumanContinuationOrdinaryInputHandoff(
    val baseline: Conversation,
    val candidate: Conversation,
    inputId: Uuid,
) {
    init {
        require(!baseline.isConsultation && candidate.messageNodes.size == baseline.messageNodes.size + 1)
        require(sameDurableGenerationSnapshot(baseline,
            candidate.copy(messageNodes = candidate.messageNodes.dropLast(1))))
        val input = candidate.messageNodes.last()
        require(input.messages.size == 1 && input.selectIndex == 0 &&
            input.currentMessage.id == inputId && input.currentMessage.role == MessageRole.USER &&
            !input.currentMessage.isSynthetic && input.currentMessage.orbisEvent == null &&
            input.currentMessage.orbisVoiceCallId == null && input.currentMessage.orbisVoiceCallKind == null)
        require(baseline.messageNodes.none { it.id == input.id || it.messages.any { message -> message.id == inputId } })
    }

    /** Already-published input or a changed page is not an unfinished handoff. Keep the
     * current headers, but seed the exact accepted first node before any second input.
     */
    fun initialFor(current: Conversation): Conversation? =
        if (sameDurableGenerationSnapshot(current, baseline)) current.copy(messageNodes = candidate.messageNodes) else null

    /** A lost completion receipt can advance the anchor only to THIS exact candidate.
     * The caller still performs its normal Room CAS against this freshly read value.
     */
    fun provesCommittedInput(stored: Conversation, expected: Conversation): Boolean =
        sameDurableGenerationSnapshot(expected, baseline) && sameDurableGenerationSnapshot(stored, candidate)
}
