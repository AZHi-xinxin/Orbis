package me.rerere.rikkahub.data.model

/** UTF-16 source offsets, matching Compose. No storage lookup or modification of message content. */
data class OrbisStickerDraftSpan(val start: Int, val end: Int, val label: String)

/** Only complete marker + valid immutable snapshot pairs become friendly draft tokens. */
fun orbisStickerDraftSpans(text: String): List<OrbisStickerDraftSpan> {
    val spans = mutableListOf<OrbisStickerDraftSpan>()
    var offset = 0
    for (segment in splitOrbisStickerReferences(text)) {
        when (segment) {
            is OrbisStickerTextSegment.Text -> offset += segment.value.length
            is OrbisStickerTextSegment.Reference -> {
                segment.tags?.let { tags ->
                    val title = tags.take(2).joinToString("、")
                    // Shorten only the displayed label, without cutting a surrogate pair.
                    val points = title.codePointCount(0, title.length)
                    val compact = if (points > 24) title.substring(0, title.offsetByCodePoints(0, 24)) + "…" else title
                    spans += OrbisStickerDraftSpan(offset, offset + segment.original.length, "[表情：$compact]")
                }
                offset += segment.original.length
            }
        }
    }
    // Defensive: never conceal a different range if the shared parser stops being lossless.
    return spans.takeIf { offset == text.length }.orEmpty()
}

data class OrbisStickerDraftDeletion(val start: Int, val end: Int)

/**
 * Expand one pure deletion to whole intersected tokens. Insertions, replacements and batched IME
 * changes return null unchanged: never swallow pasted text, select-all replacements or neighbours.
 * Surrounding newlines are not part of a token and are removed only if the user selected them.
 */
fun orbisStickerAtomicDeletion(
    original: String,
    originalStart: Int,
    originalEnd: Int,
    replacementLength: Int,
    changeCount: Int,
): OrbisStickerDraftDeletion? {
    if (changeCount != 1 || replacementLength != 0 || originalStart >= originalEnd ||
        originalStart < 0 || originalEnd > original.length) return null
    val touched = orbisStickerDraftSpans(original).filter { it.start < originalEnd && originalStart < it.end }
    if (touched.isEmpty()) return null
    val start = minOf(originalStart, touched.minOf { it.start })
    val end = maxOf(originalEnd, touched.maxOf { it.end })
    return if (start == originalStart && end == originalEnd) null else OrbisStickerDraftDeletion(start, end)
}
