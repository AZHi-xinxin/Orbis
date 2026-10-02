package me.rerere.rikkahub.data.sync.importer

/** Slices retain whitespace verbatim. No semantic guesses, HTML execution, or imported tool calls. */
internal data class OperitContentSlice(val text: String, val reasoning: Boolean = false)
internal data class OperitContentSplit(val slices: List<OperitContentSlice>, val ambiguous: Boolean = false)

/**
 * Operit stores its reasoning envelope in content. Only an AI message starting with its exact
 * envelope opts into this grammar. Subsequent envelopes must start a line. Code, quotations,
 * comments and historical tool payloads are opaque; malformed envelopes preserve the entire source.
 */
internal fun splitOperitReasoningContent(
    text: String, sender: String, checkCancelled: () -> Unit = {},
): OperitContentSplit {
    fun original(ambiguous: Boolean = false) = OperitContentSplit(listOf(OperitContentSlice(text)), ambiguous)
    if (sender != "ai" || !text.trimStart().startsWith("<think>")) return original()
    val slices = mutableListOf<OperitContentSlice>()
    var position = 0
    var lineStart = 0
    var plainStart = 0
    var reasoningStart: Int? = null
    var fence: Char? = null
    var fenceLength = 0
    fun jump(end: Int) {
        val newline = text.lastIndexOf('\n', end - 1)
        if (newline >= position) lineStart = newline + 1
        position = end
    }
    while (position < text.length) {
        if (position % 1024 == 0) checkCancelled()
        if (slices.size > 4096) return original(true)
        if (position == lineStart) {
            checkCancelled()
            val end = text.indexOf('\n', position).let { if (it < 0) text.length else it }
            var first = position
            while (first < end && text[first] == ' ') first++
            val indent = first - position
            val marker = text.getOrNull(first)
            var runEnd = first
            if (marker == '`' || marker == '~') while (runEnd < end && text[runEnd] == marker) runEnd++
            val run = runEnd - first
            if (fence != null) {
                if (indent <= 3 && marker == fence && run >= fenceLength && text.substring(runEnd, end).isBlank()) fence = null
                jump(if (end < text.length) end + 1 else end)
                continue
            }
            if (indent <= 3 && run >= 3) {
                fence = marker; fenceLength = run
                jump(if (end < text.length) end + 1 else end)
                continue
            }
            if (indent >= 4 || marker == '\t' || marker == '>') {
                jump(if (end < text.length) end + 1 else end)
                continue
            }
        }
        if (text.startsWith("<!--", position)) {
            val end = text.indexOf("-->", position + 4)
            if (end < 0) return original(true)
            jump(end + 3); continue
        }
        if (text[position] == '\\' && position + 1 < text.length) {
            jump(position + 2); continue
        }
        if (text[position] == '`') {
            var end = position
            while (end < text.length && text[end] == '`') end++
            val width = end - position
            var candidate = end
            var closed = false
            while (candidate < text.length) {
                checkCancelled()
                val tick = text.indexOf('`', candidate)
                if (tick < 0) break
                var after = tick
                while (after < text.length && text[after] == '`') after++
                if (after - tick == width) { jump(after); closed = true; break }
                candidate = after
            }
            if (!closed) return original(true)
            continue
        }
        if (text.startsWith("<tool", position)) {
            val end = text.indexOf('>', position + 5)
            if (end < 0) return original(true)
            val header = text.substring(position + 1, end)
            val name = header.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
            if (name == "tool" || name == "tool_result" || name.startsWith("tool_") || name.startsWith("tool_result_")) {
                if (header.trimEnd().endsWith('/')) jump(end + 1)
                else {
                    val close = "</$name>"
                    val closed = text.indexOf(close, end + 1)
                    if (closed < 0) return original(true)
                    jump(closed + close.length)
                }
                continue
            }
        }
        if (text.startsWith("<think>", position)) {
            if (reasoningStart != null) return original(true)
            if (text.substring(lineStart, position).isBlank()) {
                if (position > plainStart) slices += OperitContentSlice(text.substring(plainStart, position))
                reasoningStart = position + 7
                jump(position + 7); continue
            }
        }
        if (text.startsWith("</think>", position)) {
            val start = reasoningStart ?: return original(true)
            slices += OperitContentSlice(text.substring(start, position), reasoning = true)
            reasoningStart = null
            jump(position + 8)
            plainStart = position
            continue
        }
        if (text[position] == '\n') lineStart = position + 1
        position++
    }
    if (reasoningStart != null) return original(true)
    if (plainStart < text.length) slices += OperitContentSlice(text.substring(plainStart))
    return if (slices.any { it.reasoning }) OperitContentSplit(slices) else original()
}
