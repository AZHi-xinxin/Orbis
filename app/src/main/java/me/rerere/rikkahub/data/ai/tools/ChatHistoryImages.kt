package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import java.security.MessageDigest
import kotlin.uuid.Uuid

internal const val READ_CHAT_IMAGE = "orbis_read_chat_image"

/** Stable across restarts and approval resumes, scoped to the owning conversation and message. */
internal fun chatImageCode(owner: Uuid, conversation: Uuid, message: Uuid, url: String): String =
    "orbisimg_" + MessageDigest.getInstance("SHA-256")
        .digest("chat-image-v1\n$owner\n$conversation\n$message\n$url".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

private fun imageParts(parts: List<UIMessagePart>): List<UIMessagePart.Image> = parts.flatMap {
    when (it) {
        is UIMessagePart.Image -> listOf(it)
        is UIMessagePart.Tool -> imageParts(it.output)
        else -> emptyList()
    }
}

/** Request-only projection. Never writes back to chat storage or changes tool call identities. */
internal fun projectHistoryImages(messages: List<UIMessage>, owner: Uuid, conversation: Uuid): List<UIMessage> {
    val newestUser = messages.indexOfLast { it.role == MessageRole.USER }
    return messages.mapIndexed { index, message ->
        if (message.role == MessageRole.SYSTEM || (newestUser >= 0 && index >= newestUser)) message
        else {
            fun project(parts: List<UIMessagePart>): List<UIMessagePart> = parts.map { part ->
                when (part) {
                    is UIMessagePart.Image -> UIMessagePart.Text(
                        "[历史图片；图片码=${chatImageCode(owner, conversation, message.id, part.url)}；原图保留，可用 $READ_CHAT_IMAGE 按码查看。]")
                    is UIMessagePart.Tool -> part.copy(output = project(part.output))
                    else -> part
                }
            }
            val parts = project(message.parts)
            message.copy(parts = parts, usageContextInvalidated = message.usageContextInvalidated || parts != message.parts)
        }
    }
}

internal data class ChatImageReadScope(val owner: Uuid, val conversationId: Uuid,
    val tailUser: Uuid?, val epoch: Long, val sourceIds: Set<Uuid>,
    val images: Map<String, UIMessagePart.Image>) {
    fun resolve(code: String, current: Conversation?): UIMessagePart.Image {
        require(current != null && current.id == conversationId && current.assistantId == owner &&
            current.compactionEpoch == epoch && current.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id == tailUser &&
            current.currentMessages.map { it.id }.toSet().containsAll(sourceIds)) { "chat_image_scope_changed" }
        val original = images[code] ?: error("chat_image_code_unknown")
        val live = capture(current, sourceIds).images[code]
        require(live == original) { "chat_image_source_changed" }
        return original
    }
    companion object {
        fun capture(current: Conversation, sourceIds: Set<Uuid>): ChatImageReadScope {
            require(current.currentMessages.map { it.id }.toSet().containsAll(sourceIds)) { "chat_image_scope_changed" }
            val images = current.currentMessages.filter { it.id in sourceIds && it.role != MessageRole.SYSTEM }
                .flatMap { message -> imageParts(message.parts).map { image ->
                    chatImageCode(current.assistantId, current.id, message.id, image.url) to image
                } }.toMap()
            return ChatImageReadScope(current.assistantId, current.id,
                current.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id,
                current.compactionEpoch, sourceIds, images)
        }
    }
}

internal fun createChatImageReadTool(scope: ChatImageReadScope,
    read: suspend () -> Conversation?, enabled: () -> Boolean,
    present: suspend (UIMessagePart.Image) -> List<UIMessagePart> = { listOf(it) }) = Tool(
    name = READ_CHAT_IMAGE,
    description = "按历史消息中的图片码查看本对话的一张原图。传image_code；返回图片（纯文本模型使用已配置的图片识别）。每次只取一张，需要时再取；不会删除原图。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("image_code", buildJsonObject { put("type", "string") })
    }, required = listOf("image_code")) },
    needsApproval = { false },
    execute = { args ->
        try {
            require(enabled()) { "chat_image_disabled" }
            val a = args as? JsonObject
            val code = (a?.get("image_code") as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(a?.keys == setOf("image_code") && code != null && code.matches(Regex("orbisimg_[0-9a-f]{64}"))) { "chat_image_code_invalid" }
            val image = scope.resolve(code, read())
            val parts = present(image)
            require(enabled()) { "chat_image_disabled" }
            scope.resolve(code, read()) // OCR may suspend; do not deliver into a changed branch.
            listOf(UIMessagePart.Text("图片码=$code")) + parts
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", false); put("code", e.message?.takeIf { it.matches(Regex("chat_image_[a-z_]+")) } ?: "chat_image_read_failed")
            put("image_seen", false)
        }.toString())) }
    },
)
