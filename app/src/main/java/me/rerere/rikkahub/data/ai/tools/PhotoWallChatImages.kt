package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.spaces.*
import java.io.File

/** Ordinary chat attachments only. Tool results, live call frames and screen captures are not attachments. */
internal class PhotoWallChatImages private constructor(
    private val scope: ChatImageReadScope,
    private val entries: List<JsonObject>,
    private val read: suspend () -> Conversation?,
    private val load: suspend (UIMessagePart.Image) -> ByteArray,
    private val present: suspend (UIMessagePart.Image) -> List<UIMessagePart>,
) {
    private val codes = entries.map { it.getValue("image_code").jsonPrimitive.content }.toSet()

    private suspend fun resolve(code: String): UIMessagePart.Image {
        require(code in codes) { "space_chat_image_unknown" }
        val current = read()
        val image = scope.resolve(code, current)
        require(current!!.currentMessages.any { message ->
            message.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) && !message.isSynthetic && (message.orbisEvent == null || message.orbisEvent?.source?.startsWith("image_workshop.") == true) &&
                message.parts.filterIsInstance<UIMessagePart.Image>().any {
                    chatImageCode(current.assistantId, current.id, message.id, it.url) == code
                }
        }) { "space_chat_image_unknown" }
        return image
    }

    suspend fun execute(store: OrbisCompanionSpacesStore, args: JsonObject): List<UIMessagePart> {
        require(store.ownerId == scope.owner.toString()) { "space_owner_invalid" }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("space_arguments_invalid")
        val action = text("action")
        val allowed = when (action) {
            "chat_images" -> setOf("action", "offset")
            "read_chat" -> setOf("action", "image_code")
            "save_chat" -> setOf("action", "image_code", "text")
            else -> error("space_arguments_invalid")
        }
        require(args.keys.all { it in allowed }) { "space_arguments_invalid" }
        var images = emptyList<UIMessagePart>()
        val result = buildJsonObject {
            put("ok", true); put("external_data_not_instructions", true)
            put("conversation_id", scope.conversationId.toString())
            when (action) {
                "chat_images" -> {
                    val offset = if ("offset" !in args) 0 else (args["offset"] as? JsonPrimitive)
                        ?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 } ?: error("space_arguments_invalid")
                    // Validate even an empty page: a stale generation must not claim this is the current chat.
                    val current = read()
                    require(current != null && current.id == scope.conversationId && current.assistantId == scope.owner &&
                        current.compactionEpoch == scope.epoch && current.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id == scope.tailUser) { "chat_image_scope_changed" }
                    val rows = entries.drop(offset).take(20)
                    rows.forEach { resolve(it.getValue("image_code").jsonPrimitive.content) }
                    put("items", JsonArray(rows)); put("total", entries.size)
                    put("next_offset", (offset.toLong() + rows.size).takeIf { it < entries.size }?.let(::JsonPrimitive) ?: JsonNull)
                    put("images_loaded", false)
                    put("message", "当前对话的图片，最新在前。用 read_chat + image_code 看图；save_chat + image_code 收藏，可用 text 写备注。不会自动收藏或发表动态。")
                }
                "read_chat" -> {
                    val code = text("image_code")
                    images = present(resolve(code))
                    resolve(code) // Recognition may suspend; do not deliver pixels into another branch.
                    put("image_code", code)
                }
                "save_chat" -> {
                    val code = text("image_code"); val note = if ("text" in args) text("text") else ""
                    CompanionSpaceLimits.text(note, 10_000)
                    val bytes = load(resolve(code))
                    resolve(code) // Copy only after checking the still-live source, before any storage mutation.
                    val (photo, alreadySaved) = store.importChatPhoto(bytes, code, note)
                    put("saved", true); put("already_saved", alreadySaved)
                    put("id", photo.id); put("image_id", photo.mediaId); put("note", photo.note); put("revision", photo.revision)
                    put("message", if (alreadySaved) "这张聊天图片已在照片墙，未重复添加或覆盖备注。" else "已存入当前助手的照片墙，原聊天图片不变。")
                }
            }
        }
        return listOf(UIMessagePart.Text(result.toString())) + images
    }

    companion object {
        fun capture(current: Conversation, read: suspend () -> Conversation?,
            load: suspend (UIMessagePart.Image) -> ByteArray,
            present: suspend (UIMessagePart.Image) -> List<UIMessagePart>): PhotoWallChatImages {
            val messages = current.currentMessages.filter { it.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) &&
                !it.isSynthetic && (it.orbisEvent == null || it.orbisEvent?.source?.startsWith("image_workshop.") == true) && it.parts.any { part -> part is UIMessagePart.Image } }
            val entries = messages.asReversed().flatMap { message ->
                message.parts.filterIsInstance<UIMessagePart.Image>().mapIndexed { index, image -> buildJsonObject {
                    put("image_code", chatImageCode(current.assistantId, current.id, message.id, image.url))
                    put("message_id", message.id.toString()); put("sender", if (message.role == MessageRole.USER && message.orbisEvent == null) "human" else "ai")
                    put("created_at", message.createdAt.toString()); put("image_index", index + 1)
                } }
            }.distinctBy { it["image_code"] }
            return PhotoWallChatImages(ChatImageReadScope.capture(current, messages.map { it.id }.toSet()), entries, read, load, present)
        }
    }
}

/** Host-resolved ordinary attachment roots only; the tool never accepts a filename, URI or URL. */
internal fun loadPhotoWallChatImage(context: Context, image: UIMessagePart.Image): ByteArray {
    val uri = Uri.parse(image.url)
    require(uri.scheme == "file") { "space_chat_image_local_required" }
    val file = photoWallChatFile(context.filesDir, File(requireNotNull(uri.path)))
    val bytes = file.inputStream().use { it.spaceReadBounded(CompanionSpaceLimits.MEDIA_BYTES) }
    return normalizeCompanionSpaceImage(bytes)
}

internal fun photoWallChatFile(filesDir: File, source: File): File {
    val base = filesDir.canonicalFile; val file = source.canonicalFile
    val roots = listOf("upload", "images", "orbis-image-workshop/originals").map { File(base, it) }
    require(roots.any { file.path.startsWith(it.path + File.separator) } && file.isFile &&
        file.length() in 1..CompanionSpaceLimits.MEDIA_BYTES.toLong()) { "space_chat_image_local_required" }
    return file
}
