package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val orbisPrompt: OrbisConversationPrompt = OrbisConversationPrompt(),
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    // Increments only after a committed compact/rollback, protecting against stale full-state saves.
    val compactionEpoch: Long = 0,
    // Presentation/routing metadata only. A consultation uses the same assistant and tools.
    val consultation: ConsultationConversationBinding? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val isConsultation: Boolean get() = consultation != null
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.partsWithToolUndoAttachments() } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        // Transformers may change any historical message, not only the streaming tail.
        // Visit every supplied slot, but retain unchanged node/branch identities so the
        // protection merges and Compose do not reprocess the entire history per chunk.
        var newNodes: MutableList<MessageNode>? = null
        messages.forEachIndexed { index, message ->
            val node = messageNodes.getOrNull(index)
            val updated = if (node == null) {
                message.toMessageNode()
            } else {
                val existingIndex = node.messages.indexOfFirst { it.id == message.id }
                if (existingIndex >= 0 && node.selectIndex == existingIndex &&
                    node.messages[existingIndex] === message) return@forEachIndexed
                val newMessages = if (existingIndex >= 0 && node.messages[existingIndex] === message) {
                    node.messages
                } else node.messages.toMutableList().apply {
                    if (existingIndex >= 0) set(existingIndex, message) else add(message)
                }
                node.copy(
                    messages = newMessages,
                    selectIndex = if (existingIndex >= 0) existingIndex else newMessages.lastIndex,
                )
            }
            val target = newNodes ?: messageNodes.toMutableList().also { newNodes = it }
            if (index > target.lastIndex) {
                target.add(updated)
            } else {
                target[index] = updated
            }
        }
        return newNodes?.let { copy(messageNodes = it) } ?: this
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class ConsultationConversationBinding(val sessionId: String, val subject: String)

/** Map immutable model values without allocating a replacement list for a no-op. */
internal inline fun <T> List<T>.mapPreservingIdentity(transform: (T) -> T): List<T> {
    var changed: MutableList<T>? = null
    forEachIndexed { index, previous ->
        val next = transform(previous)
        if (next !== previous) {
            val target = changed ?: toMutableList().also { changed = it }
            target[index] = next
        }
    }
    return changed ?: this
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun UIMessage.partsWithToolUndoAttachments(): List<UIMessagePart> =
    parts + deletedToolRecords.map { it.tool }

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }

            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}
