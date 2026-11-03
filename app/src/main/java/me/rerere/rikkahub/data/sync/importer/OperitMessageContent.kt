package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Decodes message capabilities, not an ever-growing version whitelist. */
internal data class OperitDecodedContent(
    val text: String,
    val structured: OperitContentSplit? = null,
    val preservedUnknownSections: Int = 0,
)

internal fun decodeOperitContent(message: JsonObject, sender: String,
    checkCancelled: () -> Unit = {}): OperitDecodedContent {
    fun JsonObject.text(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("operit_content_shape")
    val sectionsValue = message["sections"]
    if (sectionsValue == null || sectionsValue == JsonNull) {
        // An explicitly empty string is valid. An unknown/missing body must not become a blank chat.
        return OperitDecodedContent(message.text("content"))
    }
    val sections = sectionsValue as? JsonArray ?: error("operit_sections_array")
    var unknown = 0
    val slices = sections.map { value ->
        checkCancelled()
        val section = value as? JsonObject ?: error("operit_section_object")
        when ((section["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "text" -> OperitContentSlice(section.text("content"))
            "thinking" -> OperitContentSlice(section.text("content"), reasoning = sender == "ai")
            "tool_call", "tool_result", "status" -> {
                // Raw historical markup stays inert Text; never construct executable Tool parts.
                val raw = section["raw"]
                if (raw is JsonPrimitive && raw.isString && raw.content.isNotEmpty()) OperitContentSlice(raw.content)
                else {
                    unknown++
                    OperitContentSlice(section.toString())
                }
            }
            else -> {
                // Preserve unfamiliar future section types as JSON text, including all fields.
                unknown++
                OperitContentSlice(section.toString())
            }
        }
    }.ifEmpty { listOf(OperitContentSlice("")) }
    val text = slices.joinToString("") { if (it.reasoning) "<think>${it.text}</think>" else it.text }
    if (message["content"] != null && message["content"] != JsonNull) {
        require(message.text("content") == text) { "operit_conflicting_content" }
    }
    return OperitDecodedContent(text, OperitContentSplit(slices), unknown)
}

internal fun OperitMessage.contentSplit(checkCancelled: () -> Unit = {}): OperitContentSplit =
    structuredContent ?: splitOperitReasoningContent(text, sender, checkCancelled)
