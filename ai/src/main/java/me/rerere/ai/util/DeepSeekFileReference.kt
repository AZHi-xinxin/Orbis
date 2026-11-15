package me.rerere.ai.util

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun checkDeepSeekFilesTarget(messages: List<UIMessage>, baseUrl: String, model: String) {
    if (messages.none { m -> m.parts.any { it.deepSeekFileId() != null } }) return
    val endpoint = baseUrl.toHttpUrlOrNull()
    require(endpoint?.host == "api.deepseek.com" && endpoint.scheme == "https" && endpoint.port == 443 &&
        endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
        model in setOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp")) {
        "DeepSeek 文件编号只可发送给官方 Flash；切换模型后请重新选择文件解析方式。"
    }
}

/** Ephemeral upload projection. Original conversation documents retain their local URL. */
fun UIMessagePart.deepSeekFileId(): String? = (this as? UIMessagePart.Document)?.metadata
    ?.get("deepseek_file_id")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
    ?.takeIf { it.matches(Regex("file-api-[A-Za-z0-9_-]+")) }

fun UIMessagePart.deepSeekFileBlock(protocol: String): JsonObject? = deepSeekFileId()?.let { id ->
    buildJsonObject {
        when (protocol) {
            "messages" -> { put("type", "document"); put("source", buildJsonObject { put("type", "file"); put("file_id", id) }) }
            "responses" -> { put("type", "input_file"); put("file_id", id) }
            "chat" -> { put("type", "file"); put("file_id", id) }
            else -> error("Unsupported file protocol")
        }
    }
}
