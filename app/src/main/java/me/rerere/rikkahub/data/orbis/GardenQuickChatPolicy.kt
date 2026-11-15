package me.rerere.rikkahub.data.orbis

const val GARDEN_QUICK_CHAT_MAX_TEXT_BYTES = 32 * 1024

/** Rejected before accepting input; unlike a missing save receipt this is known not sent. */
class GardenQuickChatSendRejected(val reasonCode: String) : IllegalStateException(when (reasonCode) {
    "garden_quick_chat_generating" -> "正在回复，请等回复结束后发送。"
    "garden_quick_chat_submitting" -> "上一条消息正在提交，请稍后发送。"
    "garden_quick_chat_queued" -> "原窗口有待处理消息，请先回原窗口核对。"
    "garden_quick_chat_pending_tool" -> "原窗口有工具等待确认，请先处理确认。"
    "garden_quick_chat_voice_active" -> "通话期间暂停快捷发送，请先结束通话。"
    "garden_quick_chat_paused" -> "这段对话已暂停，请回原窗口查看约定。"
    "garden_quick_chat_model_disabled" -> "当前模型已停用，请回原窗口选择可用模型。"
    "garden_quick_chat_empty_text" -> "请输入消息。"
    "garden_quick_chat_text_too_large" -> "快捷消息最多 32 KiB，请缩短后发送。"
    else -> "AI 或窗口状态已变化，请重新选择窗口。"
} + " 本次未发送，草稿保留。")

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
fun gardenQuickChatSendBlockReason(state: GardenQuickChatState, text: String, hasAttachments: Boolean = false): String? {
    gardenQuickChatTargetBlockReason(state)?.let { return it }
    return when {
        state.generating -> "garden_quick_chat_generating"
        state.submitting -> "garden_quick_chat_submitting"
        state.queued -> "garden_quick_chat_queued"
        state.pendingTool -> "garden_quick_chat_pending_tool"
        state.voiceActive -> "garden_quick_chat_voice_active"
        text.isBlank() && !hasAttachments -> "garden_quick_chat_empty_text"
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
    hasAttachments: Boolean = false,
): Boolean = selectedId == sentId && sentId.isNotBlank() &&
    currentRevision == sentRevision && (sentText.isNotBlank() || hasAttachments) && draft == sentText
