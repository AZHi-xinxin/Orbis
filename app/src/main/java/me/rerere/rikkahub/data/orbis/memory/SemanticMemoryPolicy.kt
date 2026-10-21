package me.rerere.rikkahub.data.orbis.memory

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import java.security.MessageDigest
import kotlin.math.sqrt

/** Disposable derived data. Text is an exact slice of a saved compaction, NOT a verbatim chat. */
internal data class SemanticMemoryChunk(
    val owner: String, val noteId: String, val revision: Int, val eventId: String,
    val createdAt: Long, val index: Int, val text: String, val vector: FloatArray,
)

internal fun memoryDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** No new authority is inferred from text, keywords or a caller-supplied note id. */
internal fun semanticNoteEligible(note: OrbisMemoryEntity): Boolean =
    !note.deleted && note.state in setOf("conditional", "static")

data class SemanticRevisionGate(val revision: Int, val state: String, val deleted: Boolean)

/** Pause/resume and delete/restore preserve automatic eligibility. Any explicit static rewrite
 * is an opt-out, even if it leaves the same text. Only state metadata is loaded, not full versions. */
internal fun semanticStaticLineageEligible(revisions: List<SemanticRevisionGate>): Boolean {
    if (revisions.isEmpty() || revisions.last() != SemanticRevisionGate(1, "static", false)) return false
    return revisions.zipWithNext().all { (now, before) ->
        now.revision == before.revision + 1 && (
            now.state == before.state && now.deleted != before.deleted ||
                now.deleted == before.deleted && setOf(now.state, before.state) == setOf("paused", "static")
            )
    }
}

/** Exact overlapping code-point slices; never split a surrogate or change the saved original. */
internal fun semanticMemorySlices(text: String, size: Int = 180, overlap: Int = 30): List<String> {
    require(size > overlap && overlap >= 0)
    val points = text.codePoints().toArray()
    if (points.isEmpty()) return emptyList()
    return buildList {
        var start = 0
        while (start < points.size) {
            val end = (start + size).coerceAtMost(points.size)
            val slice = String(points, start, end - start)
            if (slice.isNotBlank()) add(slice)
            if (end == points.size) break
            start = end - overlap
        }
    }
}

internal fun normalizedVector(values: FloatArray): FloatArray {
    require(values.isNotEmpty() && values.all(Float::isFinite))
    val norm = sqrt(values.sumOf { it.toDouble() * it })
    require(norm > 1e-12)
    return FloatArray(values.size) { (values[it] / norm).toFloat() }
}

internal fun semanticSimilarity(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size || a.isEmpty()) return -1f
    var score = 0f
    for (i in a.indices) score += a[i] * b[i]
    return score.takeIf(Float::isFinite) ?: -1f
}

internal fun semanticRecallQuery(messages: List<UIMessage>): String? {
    val human = latestMemoryHuman(messages) ?: return null
    val text = human.toText().trim().take(700)
    if (text.codePointCount(0, text.length) < 4) return null
    // Keep the current request dominant. Only ambiguous short follow-ups borrow recent human text,
    // never model/tool output (which may contain recalled notes or untrusted instructions).
    val followUp = text.length <= 32 && listOf("那个", "那次", "那件", "之前", "后来", "他呢", "她呢", "继续")
        .any(text::contains)
    val recent = if (followUp) messages.takeWhile { it.id != human.id }.asReversed().asSequence()
        .filter { it.role == MessageRole.USER && latestMemoryHuman(listOf(it)) != null }
        .map { it.toText().trim().take(100) }.filter(String::isNotEmpty).take(2).toList().asReversed() else emptyList()
    return (recent + text).joinToString("\n").takeLast(800)
}

/** Fixed per-turn selection, small quota, near-duplicate compaction slices suppressed. */
internal fun rankSemanticMemory(
    owner: String, query: FloatArray, chunks: List<SemanticMemoryChunk>,
    minimum: Float = 0.50f, margin: Float = 0.06f,
): List<SemanticMemoryChunk> {
    val ranked = chunks.asSequence().filter { it.owner == owner }
        .map { it to semanticSimilarity(query, it.vector) }
        .filter { it.second >= minimum }.sortedByDescending { it.second }.toList()
    val best = ranked.firstOrNull()?.second ?: return emptyList()
    val chosen = mutableListOf<SemanticMemoryChunk>()
    for ((chunk, score) in ranked) {
        if (score < best - margin) break
        if (chosen.any { it.noteId == chunk.noteId || it.text == chunk.text ||
                semanticSimilarity(it.vector, chunk.vector) > 0.96f }) continue
        chosen += chunk
        if (chosen.size == 3) break
    }
    return chosen
}

/**
 * BERT cased WordPiece (lowercase=false, strip_accents=null), matching the pinned tokenizer.
 * Treat literal "[SEP]" etc as ordinary input, never as model-control tokens.
 */
internal class MemoryWordPiece(vocabulary: List<String>) {
    private val vocab = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    private val unknown = checkNotNull(vocab["[UNK]"])
    private val cls = checkNotNull(vocab["[CLS]"])
    private val sep = checkNotNull(vocab["[SEP]"])

    fun encode(text: String, maxTokens: Int = 256): LongArray {
        require(maxTokens >= 2)
        val basic = mutableListOf<String>()
        val word = StringBuilder()
        fun flush() { if (word.isNotEmpty()) { basic += word.toString(); word.setLength(0) } }
        val clean = buildString {
            text.codePoints().forEach { cp ->
                val type = Character.getType(cp)
                when {
                    cp == 0 || cp == 0xfffd -> Unit
                    cp == 9 || cp == 10 || cp == 13 || type == Character.SPACE_SEPARATOR.toInt() -> append(' ')
                    type in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt()) -> Unit
                    else -> appendCodePoint(cp)
                }
            }
        }
        clean.codePoints().forEach { cp ->
            when {
                Character.isWhitespace(cp) -> flush()
                isChinese(cp) || isPunctuation(cp) -> { flush(); basic += String(Character.toChars(cp)) }
                else -> word.appendCodePoint(cp)
            }
        }
        flush()
        val ids = mutableListOf(cls)
        for (token in basic) {
            val points = token.codePoints().toArray()
            val pieces = mutableListOf<Long>()
            var start = 0
            if (points.size > 100) pieces += unknown else while (start < points.size) {
                var end = points.size
                var found: Long? = null
                while (end > start) {
                    found = vocab[(if (start > 0) "##" else "") + String(points, start, end - start)]
                    if (found != null) break
                    end--
                }
                if (found == null) { pieces.clear(); pieces += unknown; break }
                pieces += found; start = end
            }
            for (piece in pieces) {
                if (ids.size == maxTokens - 1) return (ids + sep).toLongArray()
                ids += piece
            }
        }
        return (ids + sep).toLongArray()
    }

    private fun isChinese(cp: Int) = cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf ||
        cp in 0x20000..0x2a6df || cp in 0x2a700..0x2b73f || cp in 0x2b740..0x2b81f ||
        cp in 0x2b820..0x2ceaf || cp in 0xf900..0xfaff || cp in 0x2f800..0x2fa1f
    private fun isPunctuation(cp: Int) = cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 ||
        Character.getType(cp) in setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt())
}
