package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.orbis.OrbisStickerLimits

/** UI normalization only; the repository remains the authoritative validator. Never truncate tags. */
internal fun parseStickerEditorTags(text: String): List<String> =
    text.split(Regex("[,，\\r\\n]+"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

internal fun stickerEditorError(tags: List<String>): String? = when {
    tags.isEmpty() -> "请填写表情的含义与适用场合。"
    tags.size > OrbisStickerLimits.MAX_TAGS -> "标签最多 ${OrbisStickerLimits.MAX_TAGS} 项。"
    tags.any { it.length > OrbisStickerLimits.MAX_TAG_LENGTH } ->
        "每项标签最多 ${OrbisStickerLimits.MAX_TAG_LENGTH} 个字符。"
    tags.sumOf(String::length) > OrbisStickerLimits.MAX_TAG_TOTAL_LENGTH ->
        "标签合计最多 ${OrbisStickerLimits.MAX_TAG_TOTAL_LENGTH} 个字符。"
    else -> null
}
