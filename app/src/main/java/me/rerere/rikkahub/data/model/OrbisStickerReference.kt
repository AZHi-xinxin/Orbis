package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

private val stickerIdPattern = Regex("st[0-9]{6}")
private val stickerLinePattern = Regex("\\(表情包:(st[0-9]{6})\\)")
private const val TAG_PREFIX = "[表情标签] "

/** A text-only snapshot: no image part, URI, base64, dynamic lookup or prompt-prefix injection. */
fun stickerDraftText(id: String, tags: List<String>): String {
    require(validStickerReferenceId(id))
    require(validStickerTags(tags))
    // The existing input transformer expands {placeholders} even inside JSON string values.
    // Encode user-controlled braces and angle brackets so these tags remain literal data.
    fun literal(value: String) = JsonPrimitive(value).toString()
        .replace("{", "\\u007b").replace("}", "\\u007d")
        .replace("<", "\\u003c").replace(">", "\\u003e")
    return "(表情包:$id)\n$TAG_PREFIX{\"id\":${literal(id)},\"tags\":[${tags.joinToString(",", transform = ::literal)}]}"
}

fun validStickerReferenceId(id: String): Boolean = stickerIdPattern.matches(id) && id != "st000000"

private fun validStickerTags(tags: List<String>): Boolean = tags.size in 1..12 &&
    tags.all { it.isNotBlank() && it.length <= 40 && it.none { c -> c.isISOControl() } } &&
    tags.sumOf { it.length } <= 240

sealed interface OrbisStickerTextSegment {
    data class Text(val value: String) : OrbisStickerTextSegment
    data class Reference(val id: String, val tags: List<String>?, val original: String) : OrbisStickerTextSegment
}

/** Display only. Only an exact standalone reference outside Markdown fences is recognized.
 * Arbitrary IDs/paths, inline examples, quotes and code are never interpreted as images.
 * The second line is an optional immutable tag snapshot, not a command or model instruction.
 */
fun splitOrbisStickerReferences(content: String): List<OrbisStickerTextSegment> {
    if (!content.contains("(表情包:st")) return listOf(OrbisStickerTextSegment.Text(content))
    val lines = content.split('\n')
    // Reuse the app's existing Markdown parser: fences alone miss multiline code spans
    // and lazy blockquote continuation. No new dependency and no evaluation of HTML.
    val protected = mutableListOf<IntRange>()
    val stack = ArrayDeque<ASTNode>()
    stack.add(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content))
    val excluded = setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.CODE_SPAN, MarkdownElementTypes.BLOCK_QUOTE, MarkdownElementTypes.HTML_BLOCK)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        if (node.type in excluded) protected += node.startOffset until node.endOffset
        else node.children.forEach { stack.add(it) }
    }
    val result = mutableListOf<OrbisStickerTextSegment>()
    val text = StringBuilder()
    var count = 0
    var index = 0
    var offset = 0
    fun appendLine(line: String, hasNewline: Boolean) {
        text.append(line)
        if (hasNewline) text.append('\n')
    }
    fun flush() {
        if (text.isNotEmpty()) result += OrbisStickerTextSegment.Text(text.toString())
        text.clear()
    }
    while (index < lines.size) {
        val line = lines[index]
        val clean = line.removeSuffix("\r")
        val id = if (protected.none { offset in it } && count < 12)
            stickerLinePattern.matchEntire(clean)?.groupValues?.get(1)?.takeIf(::validStickerReferenceId) else null
        if (id != null) {
            flush()
            val snapshot = lines.getOrNull(index + 1)?.removeSuffix("\r")?.let { parseStickerTagSnapshot(it, id) }
            val original = if (snapshot != null) line + "\n" + lines[index + 1] else line
            result += OrbisStickerTextSegment.Reference(id, snapshot, original)
            count++
            if (snapshot != null) { index++; offset += lines[index].length + 1 }
            if (index < lines.lastIndex) text.append('\n')
        } else {
            appendLine(line, index < lines.lastIndex)
        }
        offset += line.length + 1
        index++
    }
    flush()
    return result.ifEmpty { listOf(OrbisStickerTextSegment.Text(content)) }
}

private fun parseStickerTagSnapshot(line: String, id: String): List<String>? {
    if (!line.startsWith(TAG_PREFIX) || line.length > 4096) return null
    return runCatching {
        val obj = Json.parseToJsonElement(line.removePrefix(TAG_PREFIX)) as? JsonObject ?: return null
        if (obj.keys != setOf("id", "tags") || (obj["id"] as? JsonPrimitive)?.contentOrNull != id) return null
        val array = obj["tags"] as? JsonArray ?: return null
        val tags = array.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull ?: return null }
        tags.takeIf(::validStickerTags)
    }.getOrNull()
}
