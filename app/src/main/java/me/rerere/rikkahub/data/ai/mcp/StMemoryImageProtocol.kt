package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import java.security.MessageDigest
import kotlin.io.encoding.Base64

/** The small, explicit ST attachment contract. Never interpret arbitrary MCP JSON as an image. */
internal object StMemoryImageProtocol {
    const val STAGE_TOOL = "stage_memory_image_upload"
    const val UPLOAD_CONTRACT = "memory-image-upload/1"
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_BASE64 = 5592408
    private val reference = Regex("stimgup_[0-9a-f]{64}")
    private val digest = Regex("[0-9a-f]{64}")
    private val memoryRef = Regex("(emotion|learning|plan)://([A-Za-z0-9_-]{1,256})@([1-9][0-9]{0,9})")
    private val formats = setOf("image/png", "image/jpeg", "image/webp")

    fun supports(tools: List<McpTool>): Boolean {
        val stage = tools.firstOrNull { it.name == STAGE_TOOL && it.enable } ?: return false
        val supported = isHostUploadTool(stage)
        // Compact-profile ST publishes the host transport but routes public actions through manage.
        return supported && tools.any {
            (it.name == "store_memory_image" &&
                (it.inputSchema as? InputSchema.Obj)?.properties?.containsKey("upload_ref") == true) ||
                it.name == "stbrain_manage"
        }
    }

    fun isHostUploadTool(tool: McpTool): Boolean {
        if (tool.name != STAGE_TOOL) return false
        val schema = tool.inputSchema as? InputSchema.Obj ?: return false
        val protocol = schema.properties["protocol"] as? JsonObject ?: return false
        return protocol["const"] == JsonPrimitive(UPLOAD_CONTRACT) ||
            protocol["enum"] == JsonArray(listOf(JsonPrimitive(UPLOAD_CONTRACT)))
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun stageArguments(ref: String, mime: String, bytes: ByteArray, hash: String): JsonObject {
        require(reference.matches(ref) && mime in formats && bytes.size in 1..MAX_BYTES &&
            digest.matches(hash) && sha256(bytes) == hash) { "ST 图片附件校验失败，请重新选择图片。" }
        return buildJsonObject {
            put("protocol", UPLOAD_CONTRACT)
            put("upload_ref", ref)
            put("mime_type", mime)
            put("sha256", hash)
            put("data_base64", Base64.encode(bytes))
        }
    }

    fun validateStageReceipt(text: String, ref: String, mime: String, size: Int, hash: String) {
        // A transport acknowledgement is not a memory-write success receipt.
        val receipt = if (text.length <= 8192) runCatching {
            Json.parseToJsonElement(text) as? JsonObject
        }.getOrNull() else null
        require(receipt != null && receipt.string("contract") == UPLOAD_CONTRACT &&
            receipt.string("decision") == "staged" && receipt.string("upload_ref") == ref &&
            receipt.string("mime_type") == mime && receipt.string("sha256") == hash &&
            receipt.integer("byte_count") == size && receipt["memory_written"] == JsonPrimitive(false) &&
            receipt["write_authority_granted"] == JsonPrimitive(false) &&
            receipt.integer("expires_in_seconds") in 1..900 && dimensionsValid(receipt)
        ) { "ST 图片传输未确认，尚未提交存入；请稍后重试。" }
    }

    fun readArguments(tool: String, args: JsonObject): JsonObject? {
        val inner = when {
            tool == "read_memory_image" -> args
            tool == "stbrain_manage" && args.string("action") == "read_memory_image" ->
                args["arguments"] as? JsonObject
            else -> null
        } ?: return null
        return inner.takeIf { it["include_data"] == JsonPrimitive(true) }
    }

    data class ReadImage(val metadata: JsonObject, val bytes: ByteArray, val mimeType: String)

    /** Throws a fixed message on invalid image-bearing replies: never echo encoded pixels into history. */
    fun decodeRead(text: String, request: JsonObject): ReadImage? {
        require(text.length <= MAX_BASE64 + 16384) { "ST 图片回执超过大小限制。" }
        val value = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (value == null) {
            require(text.length <= 8192) { "ST 图片回执格式无效，未加载图片。" }
            return null
        }
        if (!value.containsKey("data_uri")) return null // Regular rejection or metadata-only reply.
        val mime = value.string("mime_type")
        val encoded = value.string("data_uri")
        require(value.string("contract_version") == "memory-images/1" &&
            value.string("decision") == "read" && value["active"] == JsonPrimitive(true) &&
            sameMemory(value.string("target_ref"), request.string("target_ref")) &&
            value.string("image_ref") != null && value["image_ref"] == request["image_ref"] &&
            mime in formats && encoded != null && encoded.startsWith("data:$mime;base64,") &&
            value.integer("byte_count") in 1..MAX_BYTES && dimensionsValid(value)
        ) { "ST 图片回执校验失败，未加载图片。" }
        val body = encoded.substringAfter(',')
        require(body.length in 1..MAX_BASE64) { "ST 图片回执超过大小限制。" }
        val bytes = try { Base64.decode(body) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("ST 图片编码无效，未加载图片。")
        }
        require(bytes.size == value.integer("byte_count") && sha256(bytes) == value.string("sha256")) {
            "ST 图片完整性校验失败，未加载图片。"
        }
        return ReadImage(JsonObject(value.filterKeys { it != "data_uri" }), bytes, mime!!)
    }

    private fun dimensionsValid(value: JsonObject): Boolean {
        val width = value.integer("width") ?: return false
        val height = value.integer("height") ?: return false
        return width in 1..16384 && height in 1..16384 && width.toLong() * height <= 20_000_000
    }

    private fun sameMemory(stored: String?, requested: String?): Boolean {
        val left = stored?.let { memoryRef.matchEntire(it) } ?: return false
        val right = requested?.let { memoryRef.matchEntire(it) } ?: return false
        // ST attachments keep their original version ref after the parent memory is revised.
        // Access to the requested version remains enforced by ST, not by this display adapter.
        return left.groupValues[1] == right.groupValues[1] && left.groupValues[2] == right.groupValues[2]
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content
    private fun JsonObject.integer(key: String): Int? = (get(key) as? JsonPrimitive)
        ?.takeIf { !it.isString }?.intOrNull
}
