package me.rerere.rikkahub.data.model

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** A presentation-only, half-open range into the unchanged original reply. */
data class OrbisReplySlice(val start: Int, val endExclusive: Int, val action: Boolean = false)

/**
 * Split top-level Markdown blocks, never sentences or soft line breaks. Whitespace belongs to
 * the preceding block (leading whitespace to the first), so joining the ranges is lossless.
 * This does not create messages, change model context, infer semantics or rewrite Markdown.
 *
 * Only complete balanced user-selected delimiters in ordinary paragraphs are eligible for
 * action styling. Complete emphasis may live inside an action or wrap only that action; slice
 * boundaries never cut an emphasis span. Other Markdown constructs take precedence.
 * Ambiguous/unfinished input stays visible.
 * A parser belongs to this invocation: streaming/concurrent conversations do not share its state.
 */
fun splitOrbisReply(content: String, settings: OrbisChatFlowSettings): List<OrbisReplySlice> {
    val original = listOf(OrbisReplySlice(0, content.length))
    if (!settings.enabled || content.isBlank()) return original

    // Reference definitions may live in another block. Rendering pieces independently would
    // break their resolution. The renderer also preprocesses LaTeX delimiters before Markdown;
    // keep those messages together, including unfinished streaming formulas.
    if (hasDocumentLatexDelimiters(content)) return original
    val tree = try {
        MarkdownParser(GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true))
            .buildMarkdownTreeFromString(content)
    } catch (_: RuntimeException) {
        return original
    }
    if (containsDocumentScopedMarkup(tree)) return original

    val blocks = tree.children.filterNot {
        it.type == MarkdownTokenTypes.EOL || it.type == MarkdownTokenTypes.WHITE_SPACE ||
            it.startOffset == it.endOffset
    }
    if (blocks.isEmpty()) return original
    val result = ArrayList<OrbisReplySlice>()
    blocks.forEachIndexed { index, block ->
        val start = if (index == 0) 0 else block.startOffset
        val end = blocks.getOrNull(index + 1)?.startOffset ?: content.length
        if (block.type != MarkdownElementTypes.PARAGRAPH || !settings.distinguishActions ||
            settings.markers.isEmpty()
        ) {
            result += OrbisReplySlice(start, end)
        } else {
            appendParagraphSlices(content, block, start, end, settings.markers, result)
        }
    }
    return result.ifEmpty { original }
}

private fun hasDocumentLatexDelimiters(content: String): Boolean {
    var index = 0
    while (index < content.length) {
        if (content[index] == '\\' && index + 1 < content.length) {
            if (content[index + 1] == '[' || content[index + 1] == '(') return true
            index += 2
        } else if (content[index] == '$' && index + 1 < content.length && content[index + 1] == '$') {
            return true
        } else {
            index++
        }
    }
    return false
}

private fun containsDocumentScopedMarkup(root: ASTNode): Boolean {
    val pending = ArrayDeque<ASTNode>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        // The lexer can retain HTML_TAG leaves inside a CODE_SPAN. Those are literal code,
        // not document-wide markup. Code remains an indivisible protected slice below;
        // its contents must not prevent unrelated paragraphs from becoming action bubbles.
        if (node.type == MarkdownElementTypes.CODE_SPAN ||
            node.type == MarkdownElementTypes.CODE_BLOCK || node.type == MarkdownElementTypes.CODE_FENCE
        ) continue
        // A blank line can end a CommonMark HTML_BLOCK before its closing HTML tag, leaving
        // its body in subsequent PARAGRAPH nodes. The existing renderer switches the *whole*
        // message to its HTML-aware renderer whenever any HTML is present; keep that same
        // scope rather than accidentally treating an inner [label] as an action.
        if (node.type == MarkdownElementTypes.LINK_DEFINITION ||
            node.type == MarkdownElementTypes.HTML_BLOCK || node.type == MarkdownTokenTypes.HTML_TAG
        ) return true
        node.children.forEach(pending::add)
    }
    return false
}

private fun appendParagraphSlices(
    content: String,
    paragraph: ASTNode,
    start: Int,
    end: Int,
    markers: Set<OrbisActionMarker>,
    result: MutableList<OrbisReplySlice>,
) {
    // Stickers are interpreted by their existing renderer together with the optional immutable
    // tag snapshot. Never split them into an action and JSON or hide malformed snapshot data.
    val paragraphText = content.substring(paragraph.startOffset, paragraph.endOffset)
    if (paragraphText.contains("(表情包:") || paragraphText.contains("[表情标签]")) {
        result += OrbisReplySlice(start, end)
        return
    }

    val protected = BooleanArray(paragraph.endOffset - paragraph.startOffset)
    // A boundary strictly inside emphasis cannot be a slice edge. Keep a difference array so
    // checking many actions remains linear, including replies with deeply nested formatting.
    val formattingCuts = IntArray(protected.size + 1)
    val formattingWrappers = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
    val pending = ArrayDeque<ASTNode>()
    paragraph.children.forEach(pending::add)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        if (node.type == MarkdownTokenTypes.HTML_TAG) {
            // An opening and closing inline HTML tag can enclose several plain-text AST nodes.
            result += OrbisReplySlice(start, end)
            return
        }
        val bareSquareLabel = node.type == MarkdownElementTypes.SHORT_REFERENCE_LINK ||
            node.type == MarkdownElementTypes.LINK_LABEL
        val emphasisSize = when (node.type) {
            MarkdownElementTypes.EMPH -> 1
            MarkdownElementTypes.STRONG -> 2
            else -> 0
        }
        val emphasisMarker = content.getOrNull(node.startOffset)
        val completeEmphasis = emphasisSize > 0 && node.endOffset - node.startOffset > emphasisSize * 2 &&
            (emphasisMarker == '*' || emphasisMarker == '_') &&
            (0 until emphasisSize).all {
                content[node.startOffset + it] == emphasisMarker &&
                    content[node.endOffset - 1 - it] == emphasisMarker
            }
        if (completeEmphasis) {
            formattingCuts[node.startOffset - paragraph.startOffset + 1]++
            formattingCuts[node.endOffset - paragraph.startOffset]--
            formattingWrappers[node.startOffset + emphasisSize to node.endOffset - emphasisSize] =
                node.startOffset to node.endOffset
            // Only formatting becomes transparent to recognition. Links/code/etc nested in
            // emphasis are still visited and protected by their original rules below.
            node.children.forEach(pending::add)
        } else if (node.children.isNotEmpty() && !bareSquareLabel ||
            node.type == MarkdownTokenTypes.AUTOLINK ||
            node.type == MarkdownTokenTypes.EMAIL_AUTOLINK ||
            node.type == MarkdownTokenTypes.URL || node.type.toString().contains("AUTOLINK")
        ) {
            protected.fill(true, node.startOffset - paragraph.startOffset,
                node.endOffset - paragraph.startOffset)
        } else {
            node.children.forEach(pending::add)
        }
    }
    for (offset in 1 until formattingCuts.size) formattingCuts[offset] += formattingCuts[offset - 1]

    fun formattedActionRange(from: Int, until: Int): Pair<Int, Int>? {
        var range = from to until
        // Expand only a wrapper whose entire content is exactly this action. Never take
        // surrounding dialogue into an action, or leave orphan ** / _ around a new bubble.
        while (true) range = formattingWrappers[range] ?: break
        return range.takeIf {
            formattingCuts[it.first - paragraph.startOffset] == 0 &&
                formattingCuts[it.second - paragraph.startOffset] == 0
        }
    }

    val selectedOpeners = markers.associateBy { it.open }
    val allOpeners = OrbisActionMarker.entries.associateBy { it.open }
    val allClosers = OrbisActionMarker.entries.mapTo(HashSet()) { it.close }
    val closers = ArrayDeque<Char>()
    var actionStart = -1
    var invalidAction = false
    var textStart = start
    var index = paragraph.startOffset
    while (index < paragraph.endOffset) {
        if (protected[index - paragraph.startOffset]) {
            if (actionStart >= 0) invalidAction = true
            index++
            continue
        }
        val character = content[index]
        if (character == '\\') {
            // Escaped opening/closing brackets are literal, as are escaped backticks/dollars.
            index = (index + 2).coerceAtMost(paragraph.endOffset)
            continue
        }
        if (character == '$' || character == '`') {
            // An unmatched inline-math/code opener during streaming must not hide its tail.
            // Complete forms are already protected by the Markdown AST above.
            break
        }
        if (actionStart < 0) {
            val marker = selectedOpeners[character]
            if (marker != null) {
                actionStart = index
                invalidAction = index > paragraph.startOffset && content[index - 1] == '!'
                closers.addLast(marker.close)
            }
        } else {
            val nested = allOpeners[character]
            if (nested != null) {
                closers.addLast(nested.close)
            } else if (character in allClosers) {
                if (closers.last() != character) {
                    // Mismatched delimiters are not a completed action. Keep this entire tail.
                    break
                }
                closers.removeLast()
                if (closers.isEmpty()) {
                    val after = index + 1
                    // The AST may see a not-yet-complete Markdown link as just [its label].
                    // Do not briefly collapse it while the destination/reference is streaming.
                    val pendingLink = character == ']' && after < paragraph.endOffset &&
                        (content[after] == '(' || content[after] == '[')
                    if (pendingLink) break
                    val range = if (!invalidAction && !isCitation(content, actionStart, after))
                        formattedActionRange(actionStart, after) else null
                    if (range != null) {
                        if (textStart < range.first) result += OrbisReplySlice(textStart, range.first)
                        result += OrbisReplySlice(range.first, range.second, action = true)
                        textStart = range.second
                        index = range.second - 1
                    }
                    actionStart = -1
                }
            }
        }
        index++
    }
    // Incomplete/mismatched candidates and all trailing whitespace survive exactly as written.
    if (textStart < end) result += OrbisReplySlice(textStart, end)
}

private fun isCitation(content: String, start: Int, end: Int): Boolean {
    if (content[start] != '[' && content[start] != '【') return false
    val inside = content.substring(start + 1, end - 1)
    return inside.contains('†') || inside.startsWith("citation", ignoreCase = true) ||
        inside.startsWith('^') || inside.isNotEmpty() && inside.all {
            it.isDigit() || it.isWhitespace() || it in ",;，、-"
        }
}
