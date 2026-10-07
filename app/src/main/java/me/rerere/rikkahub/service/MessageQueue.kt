package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.OrbisUserMessageTime
import me.rerere.ai.ui.OrbisMessageQuote
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.ai.KnownEmptyCompletionFailure
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallProtocol
import kotlin.uuid.Uuid

data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val isEditing: Boolean = false,
    // Optional in-memory observer; null result means the queued message was withdrawn.
    val reply: CompletableDeferred<String?>? = null,
    val orbisEventId: String? = null,
    val voiceCallId: String? = null,
    val voiceCallKind: String? = null,
    val orbisUserMessageTime: OrbisUserMessageTime? = null,
    val orbisQuote: OrbisMessageQuote? = null,
    // Still visible/removable in the queue; never an automatic replay permission.
    val recoveryHeldReason: String? = null,
    // A previously queued message is not a new human acknowledgement of a later safety hold.
    val acknowledgeSafetyHold: Boolean = true,
    val freshHumanInputPermit: FreshHumanInputPermit? = null,
)

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

/** Held call records stay visible, but are not a pending send. A real pause still blocks a new tap. */
internal fun MessageQueueState.blocksImmediateInput(): Boolean =
    paused || messages.any { it.recoveryHeldReason == null }

private fun QueuedMessage.isHumanEditableInput(): Boolean =
    orbisEventId == null && ((voiceCallId == null && voiceCallKind == null) ||
        (voiceCallId != null && voiceCallKind == "turn"))

internal fun unreferencedQueuedAttachmentUrls(
    previous: QueuedMessage,
    conversations: List<Conversation>,
    pendingMessages: List<QueuedMessage>,
): Set<String> {
    val retainedParts = conversations.flatMap { conversation ->
        conversation.messageNodes.flatMap { node -> node.messages.flatMap { it.parts } }
    } + pendingMessages.flatMap { it.parts }
    return previous.parts.localFileUrls() - retainedParts.localFileUrls()
}

/** Pending input is kept outside conversation history until dispatched. */
class MessageQueuePausedException : IllegalStateException()

class MessageQueue(
    initiallyPaused: Boolean = false,
    private val onPauseChanged: (Boolean) -> Boolean = { true },
) {
    private val mutableState = MutableStateFlow(MessageQueueState(paused = initiallyPaused))
    val state = mutableState.asStateFlow()

    @Synchronized
    fun enqueue(parts: List<UIMessagePart>, answer: Boolean = true, reply: CompletableDeferred<String?>? = null,
        id: Uuid = Uuid.random(), orbisEventId: String? = null,
        voiceCallId: String? = null, voiceCallKind: String? = null,
        orbisUserMessageTime: OrbisUserMessageTime? = null, orbisQuote: OrbisMessageQuote? = null,
        freshHumanInputPermit: FreshHumanInputPermit? = null) {
        if (state.value.messages.any { it.id == id }) {
            reply?.completeExceptionally(IllegalStateException("duplicate_queue_id"))
            return
        }
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            return
        }
        mutableState.value = state.value.copy(
            messages = state.value.messages + QueuedMessage(
                id = id,
                parts = parts.toList(),
                answer = answer,
                reply = reply,
                orbisEventId = orbisEventId,
                voiceCallId = voiceCallId,
                voiceCallKind = voiceCallKind,
                orbisUserMessageTime = orbisUserMessageTime,
                orbisQuote = orbisQuote,
                freshHumanInputPermit = freshHumanInputPermit,
                acknowledgeSafetyHold = freshHumanInputPermit == null,
            ),
        )
    }

    @Synchronized
    fun takeNext(allow: (QueuedMessage) -> Boolean = { true }): QueuedMessage? {
        val current = state.value
        if (current.paused) return null
        val next = current.messages.firstOrNull { it.recoveryHeldReason == null && allow(it) }
            ?.takeUnless { it.isEditing } ?: return null
        mutableState.value = current.copy(messages = current.messages.filterNot { it.id == next.id })
        return next
    }

    /** Recovery cannot replay stale call protocol or silently discard accepted human dictation. */
    @Synchronized
    internal fun holdCallInputsForRecovery(): Int {
        mutableState.value = state.value.copy(messages = state.value.messages.map { message ->
            if (message.voiceCallId != null || message.voiceCallKind != null)
                message.copy(recoveryHeldReason = "previous_call_input", acknowledgeSafetyHold = false)
            else message.copy(acknowledgeSafetyHold = false)
        })
        return state.value.messages.count { it.recoveryHeldReason != null }
    }

    /** Downgrade never replays old human text either. Original parts/order/id remain visible. */
    @Synchronized
    internal fun holdAllInputsForFreshRecovery(): Int = holdInputsForRecovery(state.value.messages.map { it.id }.toSet())

    @Synchronized
    internal fun holdInputsForRecovery(ids: Set<Uuid>): Int {
        mutableState.value = state.value.copy(messages = state.value.messages.map { message ->
            if (message.id !in ids) return@map message
            message.freshHumanInputPermit?.completed = true
            message.copy(recoveryHeldReason = "previous_input_before_fresh_recovery",
                acknowledgeSafetyHold = false, freshHumanInputPermit = null)
        })
        return state.value.messages.count { it.id in ids }
    }

    /** Caller proves no history write/provider/tool dispatch was attempted. Preserve original order/id. */
    @Synchronized
    internal fun retainUndispatched(message: QueuedMessage) {
        if (state.value.messages.none { it.id == message.id }) {
            mutableState.value = state.value.copy(messages = listOf(message) + state.value.messages)
        }
        pause() // explicit review, never an immediate replay loop
    }

    @Synchronized
    fun remove(id: Uuid): QueuedMessage? {
        val removed = state.value.messages.find { it.id == id } ?: return null
        mutableState.value =
            state.value.copy(messages = state.value.messages.filterNot { it.id == id })
        removed.reply?.complete(null)
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val message = state.value.messages.find { it.id == id && !it.isEditing && it.orbisEventId == null } ?: return null
        if (message.recoveryHeldReason != null && !message.isHumanEditableInput()) return null
        val editable = if (message.recoveryHeldReason != null && message.voiceCallKind == "turn")
            message.humanTurnDraft() ?: return null else message
        mutableState.value = state.value.copy(
            messages = state.value.messages.map { if (it.id == id) it.copy(isEditing = true) else it },
        )
        return editable
    }

    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null,
        freshHumanInputPermit: FreshHumanInputPermit? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val previous = state.value.messages.find { it.id == id && it.orbisEventId == null } ?: return null
        if (previous.recoveryHeldReason != null && parts != null &&
            (!previous.isEditing || !previous.isHumanEditableInput() ||
                previous.voiceCallKind == "turn" && previous.humanTurnDraft() == null)) return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map {
                if (it.id == id) (if (it.recoveryHeldReason != null && parts != null)
                    it.copy(voiceCallId = null, voiceCallKind = null, reply = null, recoveryHeldReason = null)
                    else it).copy(
                    parts = parts?.toList() ?: it.parts,
                    freshHumanInputPermit = if (parts != null) freshHumanInputPermit else it.freshHumanInputPermit,
                    acknowledgeSafetyHold = if (parts != null) freshHumanInputPermit == null else it.acknowledgeSafetyHold,
                    isEditing = false
                ) else it
            },
        )
        // 取消编辑只释放占位，不能清理原附件。
        return previous.takeIf { parts != null }
    }

    @Synchronized
    fun pause() {
        mutableState.value = state.value.copy(paused = true)
        state.value.messages.forEach { it.reply?.completeExceptionally(MessageQueuePausedException()) }
        // Even when storage is unavailable, this live queue must stop immediately.
        onPauseChanged(true)
    }

    /** Never resume an existing human/unknown pause and never re-enqueue the failed input. */
    internal fun afterGenerationFailure(error: Throwable, partialSnapshotSaved: Boolean) {
        if (!partialSnapshotSaved || (error !is KnownEmptyCompletionFailure && !error.isSavedVoiceInterruption())) pause()
    }

    fun failReplyWaiters(message: String) {
        state.value.messages.forEach {
            it.reply?.completeExceptionally(IllegalStateException(message))
        }
    }

    @Synchronized
    fun resume() {
        // Clearing a durable pause must succeed BEFORE any in-memory item can leave the queue.
        if (!onPauseChanged(false)) return
        mutableState.value = state.value.copy(paused = false)
    }
}

/** Remove only the exact host-created first line for this typed turn, not marker-like human prose. */
private fun QueuedMessage.humanTurnDraft(): QueuedMessage? {
    val callId = voiceCallId ?: return null
    val text = parts.singleOrNull() as? UIMessagePart.Text ?: return null
    val prefix = runCatching { OrbisVoiceCallProtocol.userTurn(callId, "_").removeSuffix("_") }.getOrNull() ?: return null
    if (!text.text.startsWith(prefix)) return null
    return copy(parts = listOf(UIMessagePart.Text(text.text.removePrefix(prefix))))
}
