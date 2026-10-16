package me.rerere.rikkahub.data.model

/** Bounded rows without cutting a surrogate pair; used only for local immutable originals. */
internal fun originalArchiveChunks(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        var end = minOf(text.length, start + 16_000)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        add(text.substring(start, end)); start = end
    }
}
