package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

/** The local extension picker edits memberships, never a stale whole-chat snapshot. */
data class ConversationInjectionSelection(
    val modeIds: Set<Uuid>,
    val lorebookIds: Set<Uuid>,
) {
    fun applyTo(conversation: Conversation): Conversation =
        conversation.copy(modeInjectionIds = modeIds, lorebookIds = lorebookIds)

    companion object {
        fun from(conversation: Conversation) =
            ConversationInjectionSelection(conversation.modeInjectionIds, conversation.lorebookIds)
    }
}

data class ConversationInjectionEdit(
    val conversationId: Uuid,
    val assistantId: Uuid,
    val addedModes: Set<Uuid>,
    val removedModes: Set<Uuid>,
    val addedLorebooks: Set<Uuid>,
    val removedLorebooks: Set<Uuid>,
) {
    fun applyTo(current: Conversation): ConversationInjectionSelection {
        require(current.id == conversationId && current.assistantId == assistantId) { "会话归属已变化，请重新打开扩展面板。" }
        return ConversationInjectionSelection(
            (current.modeInjectionIds - removedModes) + addedModes,
            (current.lorebookIds - removedLorebooks) + addedLorebooks,
        )
    }

    companion object {
        fun between(before: Conversation, after: Conversation): ConversationInjectionEdit {
            require(before.id == after.id && before.assistantId == after.assistantId)
            return ConversationInjectionEdit(before.id, before.assistantId,
                after.modeInjectionIds - before.modeInjectionIds, before.modeInjectionIds - after.modeInjectionIds,
                after.lorebookIds - before.lorebookIds, before.lorebookIds - after.lorebookIds)
        }
    }
}
