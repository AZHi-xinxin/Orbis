package me.rerere.rikkahub.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.humanThoughtPart
import me.rerere.ai.ui.isHumanThought
import kotlin.uuid.Uuid

class ChatInputState {
    var orbisQuote by mutableStateOf<me.rerere.ai.ui.OrbisMessageQuote?>(null)
    val textContent = TextFieldState()
    val humanThoughtContent = TextFieldState()
    // Only controls the editor's presentation; folded drafts are still part of the message.
    var humanThoughtExpanded by mutableStateOf(false)
    var hasExistingHumanThought by mutableStateOf(false)
        private set
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()

    fun clearInput(preserveHumanThought: Boolean = false) {
        textContent.setTextAndPlaceCursorAtEnd("")
        if (!preserveHumanThought) humanThoughtContent.setTextAndPlaceCursorAtEnd("")
        humanThoughtExpanded = false
        hasExistingHumanThought = false
        messageContent = emptyList()
        editingMessage = null
        editingParts = null
        editingAttachmentUrls = emptySet()
        orbisQuote = null
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun setContents(contents: List<UIMessagePart>) {
        humanThoughtExpanded = false
        hasExistingHumanThought = contents.any { it.isHumanThought() }
        humanThoughtContent.setTextAndPlaceCursorAtEnd(contents.filter { it.isHumanThought() }
            .filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
        val bodyContents = contents.filterNot { it.isHumanThought() }
        val lastTextIndex = bodyContents.indexOfLast { it is UIMessagePart.Text }
        val text = if (lastTextIndex >= 0) {
            (bodyContents[lastTextIndex] as UIMessagePart.Text).text
        } else {
            ""
        }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = bodyContents.filter { it !is UIMessagePart.Text }
        editingParts = bodyContents
        editingAttachmentUrls = contents.mapNotNull { it.attachmentUrlOrNull() }.toSet()
    }

    fun getContents(humanThoughtEnabled: Boolean = false): List<UIMessagePart> {
        val includeThought = if (isEditing()) hasExistingHumanThought else humanThoughtEnabled || hasExistingHumanThought
        val body = getBodyContents()
        return if (includeThought) listOf(humanThoughtPart(humanThoughtContent.text.toString())) + body else body
    }

    private fun getBodyContents(): List<UIMessagePart> {
        val text = textContent.text.toString()
        if (isEditing()) {
            val originalParts = editingParts
            if (originalParts != null) {
                val editedTextIndex = originalParts.indexOfLast { it is UIMessagePart.Text }
                val remainingAttachments = messageContent.toMutableList()
                val merged = mutableListOf<UIMessagePart>()

                originalParts.forEachIndexed { index, part ->
                    when {
                        index == editedTextIndex -> {
                            if (text.isNotBlank()) {
                                merged.add(UIMessagePart.Text(text))
                            }
                        }

                        part is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                merged.add(part)
                            }
                        }

                        else -> {
                            val currentIndex = remainingAttachments.indexOf(part)
                            if (currentIndex >= 0) {
                                merged.add(remainingAttachments.removeAt(currentIndex))
                            }
                        }
                    }
                }
                if (editedTextIndex < 0 && text.isNotBlank()) {
                    merged.add(0, UIMessagePart.Text(text))
                }
                // Newly added attachments are appended in insertion order.
                merged.addAll(remainingAttachments)
                return merged
            }
            return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
        }
        return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
    }

    fun isEmpty(): Boolean {
        return textContent.text.isBlank() && messageContent.isEmpty()
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Image(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }

    /**
     * 仅删除当前输入组件临时新增的本地文件。
     * 编辑历史消息时，原有附件不在这里删除，由会话层统一做差异清理。
     */
    fun shouldDeleteFileOnRemove(part: UIMessagePart): Boolean {
        val url = part.attachmentUrlOrNull() ?: return false
        if (!url.startsWith("file:")) return false
        return !isEditing() || url !in editingAttachmentUrls
    }

    private fun UIMessagePart.attachmentUrlOrNull(): String? {
        return when (this) {
            is UIMessagePart.Image -> this.url
            is UIMessagePart.Video -> this.url
            is UIMessagePart.Audio -> this.url
            is UIMessagePart.Document -> this.url
            else -> null
        }
    }
}
