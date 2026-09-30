package me.rerere.rikkahub.data.orbis

const val GARDEN_QUICK_CHAT_MAX_TEXT_BYTES = 32 * 1024

/**
 * A current native snapshot, never values trusted from the garden page. targetAssistantId is
 * captured when the drawer opens; the other owner and availability fields must be read again
 * inside the service's send critical section. This pure policy does not acquire that lock.
 */
data class GardenQuickChatState(
    val currentAssistantId: String,
    val targetAssistantId: String,
    val conversationAssistantId: String?,
    val assistantExists: Boolean,
    val conversationExists: Boolean,
    val initialized: Boolean,
    val generating: Boolean = false,
    val submitting: Boolean = false,
    val queued: Boolean = false,
    val pendingTool: Boolean = false,
    val voiceActive: Boolean = false,
)

/** Existing conversations only: a changed or missing owner never falls back to another assistant. */
fun gardenQuickChatTargetBlockReason(state: GardenQuickChatState): String? = when {
    state.targetAssistantId.isBlank() || state.currentAssistantId.isBlank() ->
        "garden_quick_chat_invalid_assistant"
    !state.assistantExists -> "garden_quick_chat_assistant_missing"
    state.currentAssistantId != state.targetAssistantId -> "garden_quick_chat_assistant_changed"
    !state.conversationExists -> "garden_quick_chat_conversation_missing"
    state.conversationAssistantId != state.targetAssistantId -> "garden_quick_chat_owner_changed"
    !state.initialized -> "garden_quick_chat_not_initialized"
    else -> null
}

fun gardenQuickChatTargetAllowed(state: GardenQuickChatState): Boolean =
    gardenQuickChatTargetBlockReason(state) == null

/** A quick send is immediate or refused; it must never become a queued or automatic submission. */
fun gardenQuickChatSendBlockReason(state: GardenQuickChatState, text: String): String? {
    gardenQuickChatTargetBlockReason(state)?.let { return it }
    return when {
        state.generating -> "garden_quick_chat_generating"
        state.submitting -> "garden_quick_chat_submitting"
        state.queued -> "garden_quick_chat_queued"
        state.pendingTool -> "garden_quick_chat_pending_tool"
        state.voiceActive -> "garden_quick_chat_voice_active"
        text.isBlank() -> "garden_quick_chat_empty_text"
        text.toByteArray(Charsets.UTF_8).size > GARDEN_QUICK_CHAT_MAX_TEXT_BYTES ->
            "garden_quick_chat_text_too_large"
        else -> null
    }
}

fun canSendGardenQuickChat(state: GardenQuickChatState, text: String): Boolean =
    gardenQuickChatSendBlockReason(state, text) == null

/** Clear only the exact draft whose user message was confirmed persisted, never a later edit. */
fun canClearGardenQuickChatDraft(
    selectedId: String?,
    sentId: String,
    currentRevision: Int,
    sentRevision: Int,
    draft: String,
    sentText: String,
): Boolean = selectedId == sentId && sentId.isNotBlank() &&
    currentRevision == sentRevision && sentText.isNotBlank() && draft == sentText
