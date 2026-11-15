package me.rerere.rikkahub.data.files

import me.rerere.ai.ui.UIMessagePart

/** Tool receipts can contain images too. Keep every receipt field and image metadata. */
internal suspend fun localizeInlineImageParts(
    parts: List<UIMessagePart>,
    persist: suspend (String) -> String,
): List<UIMessagePart> {
    val saved = mutableMapOf<String, String>()
    suspend fun visit(items: List<UIMessagePart>, depth: Int): List<UIMessagePart> {
        check(depth <= 32) { "image_part_nesting_limit" }
        return items.map { part ->
            when (part) {
                is UIMessagePart.Image -> if (part.url.startsWith("data:image/")) {
                    val uri = saved[part.url] ?: persist(part.url).also { saved[part.url] = it }
                    check(uri.startsWith("file://")) { "image_local_file_required" }
                    part.copy(url = uri)
                } else part
                is UIMessagePart.Tool -> part.copy(output = visit(part.output, depth + 1))
                else -> part
            }
        }
    }
    return visit(parts, 0)
}

internal fun List<UIMessagePart>.hasInlineImageParts(): Boolean = any {
    when (it) {
        is UIMessagePart.Image -> it.url.startsWith("data:image/")
        is UIMessagePart.Tool -> it.output.hasInlineImageParts()
        else -> false
    }
}
