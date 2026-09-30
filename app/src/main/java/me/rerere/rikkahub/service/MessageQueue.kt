package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.ai.KnownEmptyCompletionFailure
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
)

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

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
        voiceCallId: String? = null, voiceCallKind: String? = null) {
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
            ),
        )
    }

    @Synchronized
    fun takeNext(): QueuedMessage? {
        val current = state.value
        if (current.paused) return null
        val next = current.messages.firstOrNull()?.takeUnless { it.isEditing } ?: return null
        mutableState.value = current.copy(messages = current.messages.drop(1))
        return next
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
        mutableState.value = state.value.copy(
            messages = state.value.messages.map { if (it.id == id) it.copy(isEditing = true) else it },
        )
        return message
    }

    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val previous = state.value.messages.find { it.id == id && it.orbisEventId == null } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map {
                if (it.id == id) it.copy(
                    parts = parts?.toList() ?: it.parts,
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
