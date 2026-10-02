package me.rerere.rikkahub.utils

/** Keep the existing UTF-16 size limit without leaving half of a supplementary character. */
internal fun String.takeAtCodePointBoundary(limit: Int): String {
    require(limit >= 0)
    var end = minOf(limit, length)
    if (end > 0 && end < length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) {
        end--
    }
    return substring(0, end)
}
