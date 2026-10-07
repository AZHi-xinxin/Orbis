package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.uuid.Uuid

internal const val ST_IMAGE_LIST_TOOL = "orbis_list_current_images"
internal const val ST_IMAGE_STAGE_TOOL = "stage_memory_image_upload"
internal const val ST_IMAGE_MAX_BYTES = 4 * 1024 * 1024
private const val MAX_CURRENT_IMAGES = 8

/** Only used after the server explicitly advertises the upload-reference protocol. */
internal fun stImageReferenceSchema(schema: InputSchema?): InputSchema {
    val source = schema as? InputSchema.Obj ?: error("st_image_schema_incompatible")
    require("upload_ref" in source.properties) { "st_image_schema_incompatible" }
    return source.copy(
        properties = JsonObject((source.properties - "data_base64") + ("upload_ref" to buildJsonObject {
            put("type", "string"); put("pattern", "^stimgup_[0-9a-f]{64}$")
            put("description", "orbis_list_current_images 返回的非空图片引用，原样传回。")
        })),
        required = (source.required.orEmpty().filterNot { it == "data_base64" } + "upload_ref").distinct(),
    )
}

/** Captured by the host; neither a model-supplied conversation nor an arbitrary file path. */
internal data class StImageAttachmentScope(
    val assistantId: Uuid,
    val conversationId: Uuid,
    val userMessageId: Uuid?,
    val branchTailUserMessageId: Uuid? = userMessageId,
) {
    fun images(conversation: Conversation?): List<StChatImage> {
        require(conversation != null && conversation.id == conversationId &&
            conversation.assistantId == assistantId) { "st_image_conversation_changed" }
        val branch = conversation.currentMessages
        require(branch.lastOrNull { it.role == MessageRole.USER }?.id == branchTailUserMessageId) {
            "st_image_source_message_changed"
        }
        if (userMessageId == null) return emptyList()
        val user = branch.singleOrNull { it.id == userMessageId }
        require(user != null && user.role == MessageRole.USER) { "st_image_source_message_changed" }
        if (user.orbisEvent != null || user.isSynthetic) return emptyList()
        val images = user.parts.mapIndexedNotNull { index, part ->
            (part as? UIMessagePart.Image)?.let { StChatImage(index, it.url) }
        }
        require(images.size <= MAX_CURRENT_IMAGES) { "st_image_too_many_current_images" }
        return images
    }

    companion object {
        fun capture(assistantId: Uuid, conversationId: Uuid, conversation: Conversation?,
                    sourceMessageIds: Set<Uuid>): StImageAttachmentScope {
            require(conversation != null && conversation.id == conversationId &&
                conversation.assistantId == assistantId) { "st_image_conversation_changed" }
            val branch = conversation.currentMessages
            require(branch.map { it.id }.toSet().containsAll(sourceMessageIds)) { "st_image_source_scope_changed" }
            return StImageAttachmentScope(assistantId, conversationId,
                branch.lastOrNull { it.id in sourceMessageIds && it.role == MessageRole.USER }?.id,
                branch.lastOrNull { it.role == MessageRole.USER }?.id)
        }
    }
}

internal data class StChatImage(val partIndex: Int, val url: String)
internal data class StImageOriginal(val bytes: ByteArray, val mimeType: String) {
    val sha256: String get() = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}
internal data class StImageDestination(val serverId: Uuid, val revision: String, val serverName: String, val storeTool: String)
internal data class StPreparedImage(val uploadRef: String, val mimeType: String, val sha256: String, val byteCount: Int)

/** Process-local, bounded reservations. No bytes, credentials or filesystem paths are serialized. */
internal class StMemoryImageAttachmentRegistry(
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val lifetimeMillis: Long = 24 * 60 * 60 * 1000L,
    private val capacity: Int = 256,
) {
    private data class Binding(val scope: StImageAttachmentScope, val serverId: Uuid, val revision: String,
        val image: StChatImage, val sha256: String, val mimeType: String, val byteCount: Int)
    private data class Entry(val binding: Binding, val prepared: StPreparedImage, val createdAt: Long)
    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun reserve(scope: StImageAttachmentScope, destination: StImageDestination, image: StChatImage,
                original: StImageOriginal): StPreparedImage {
        require(original.bytes.size in 1..ST_IMAGE_MAX_BYTES && original.mimeType in IMAGE_MIMES) { "st_image_invalid_original" }
        prune()
        val binding = Binding(scope, destination.serverId, destination.revision, image,
            original.sha256, original.mimeType, original.bytes.size)
        entries.values.firstOrNull { it.binding == binding }?.let { return it.prepared }
        while (entries.size >= capacity) entries.remove(entries.keys.first())
        val ref = "stimgup_" + ByteArray(32).also(random::nextBytes).toHex()
        val prepared = StPreparedImage(ref, binding.mimeType, binding.sha256, binding.byteCount)
        entries[ref] = Entry(binding, prepared, now())
        return prepared
    }

    suspend fun resolve(uploadRef: String, scope: StImageAttachmentScope, destination: StImageDestination,
                        currentImages: List<StChatImage>, readOriginal: suspend (String) -> StImageOriginal): StImageOriginal {
        val entry = synchronized(this) {
            prune()
            entries[uploadRef] ?: error("st_image_reference_expired_or_unknown")
        }
        val binding = entry.binding
        require(binding.scope == scope && binding.serverId == destination.serverId &&
            binding.revision == destination.revision && binding.image in currentImages) { "st_image_reference_scope_mismatch" }
        val original = readOriginal(binding.image.url)
        require(original.bytes.size == binding.byteCount && original.mimeType == binding.mimeType &&
            original.sha256 == binding.sha256) { "st_image_original_changed" }
        return original
    }

    private fun prune() { entries.entries.removeAll { now() - it.value.createdAt >= lifetimeMillis } }
    companion object { val IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/webp") }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

/** Listing reads only the current user message. It does not contact ST or grant upload authority. */
internal fun createStImageListTool(
    scope: StImageAttachmentScope,
    destinations: List<StImageDestination>,
    registry: StMemoryImageAttachmentRegistry,
    readConversation: suspend () -> Conversation?,
    readOriginal: suspend (String) -> StImageOriginal,
    isCurrent: () -> Boolean,
): Tool = Tool(
    name = ST_IMAGE_LIST_TOOL,
    description = "列出本次生成范围最后一条用户消息中的图片，获得对应 ST 的 upload_ref、mime_type 和原件 SHA-256。" +
        "仅本机读取，不上传、不存入记忆。需要记住图片时先创建或找到目标记忆，再把返回的 upload_ref 原样交给该 ST 的 store_memory_image；" +
        "不要生成 data_base64、使用文件路径或将其它消息图片冒充本次图片。引用失效时重新列出，不自动重放结果未知的存图。",
    parameters = { InputSchema.Obj(buildJsonObject {}, required = emptyList()) },
    hostApproval = HostToolApproval("orbis:st_current_images", "st-current-images-v1", "读取当前聊天图片引用"),
    isApprovalCurrent = isCurrent,
    execute = { value -> withContext(Dispatchers.IO) {
        require(value is JsonObject && value.isEmpty()) { "st_image_invalid_list_arguments" }
        check(isCurrent()) { "st_image_target_changed" }
        val images = scope.images(readConversation())
        val items = buildJsonArray {
            images.forEachIndexed { number, image ->
                val original = readOriginal(image.url)
                destinations.forEach { destination ->
                    val prepared = registry.reserve(scope, destination, image, original)
                    add(buildJsonObject {
                        put("image_number", number + 1); put("server", destination.serverName)
                        put("store_tool", destination.storeTool); put("upload_ref", prepared.uploadRef)
                        if (destination.storeTool.endsWith("__stbrain_manage")) put("action", "store_memory_image")
                        put("mime_type", prepared.mimeType); put("sha256", prepared.sha256)
                        put("byte_count", prepared.byteCount); put("state", "prepared_not_uploaded")
                    })
                }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("images", items); put("memory_written", false); put("uploaded", false)
        }.toString()))
    } },
)

/** The original model arguments (including execution_ref) are passed unchanged to the final call. */
internal fun wrapStImageUploadTool(
    tool: Tool,
    mcpToolName: String,
    scope: StImageAttachmentScope,
    destination: StImageDestination,
    registry: StMemoryImageAttachmentRegistry,
    readConversation: suspend () -> Conversation?,
    readOriginal: suspend (String) -> StImageOriginal,
    stage: suspend (String, StImageOriginal) -> Unit,
): Tool = tool.copy(execute = { value ->
    val args = value as? JsonObject ?: error("st_image_invalid_arguments")
    val imageArgs = when {
        mcpToolName == "store_memory_image" -> args
        mcpToolName == "stbrain_manage" && args["action"]?.jsonPrimitive?.contentOrNull == "store_memory_image" ->
            args["arguments"] as? JsonObject ?: error("st_image_invalid_arguments")
        else -> null
    }
    if (imageArgs != null) {
        require(imageArgs["data_base64"] == null || imageArgs["data_base64"] == JsonNull) { "st_image_use_upload_ref" }
        val ref = imageArgs["upload_ref"]?.jsonPrimitive?.takeIf { it.isString }?.content
            ?: error("st_image_use_upload_ref")
        val mime = imageArgs["mime_type"]?.jsonPrimitive?.takeIf { it.isString }?.content
            ?: error("st_image_mime_required")
        check(tool.isApprovalCurrent()) { "st_image_target_changed" }
        val original = withContext(Dispatchers.IO) {
            registry.resolve(ref, scope, destination, scope.images(readConversation()), readOriginal)
        }
        require(mime == original.mimeType) { "st_image_mime_mismatch" }
        stage(ref, original)
        // Upload staging is not memory-write authorization. Recheck target and source before dispatch.
        check(tool.isApprovalCurrent()) { "st_image_target_changed" }
        withContext(Dispatchers.IO) {
            registry.resolve(ref, scope, destination, scope.images(readConversation()), readOriginal)
        }
        check(tool.isApprovalCurrent()) { "st_image_target_changed" }
    }
    tool.execute(value)
})
